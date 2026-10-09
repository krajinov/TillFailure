import Foundation
import Darwin
import Shared

#if DEBUG
/// Opt-in signed-simulator proof using the product Swift Firebase and encrypted recovery adapters.
enum CleanDepartureHarness {
    private static let resultURL = FileManager.default.temporaryDirectory.appendingPathComponent("pr1-departure-results.txt")

    private static func record(_ line: String) {
        print(line)
        let data = Data((line + "\n").utf8)
        if FileManager.default.fileExists(atPath: resultURL.path),
           let file = try? FileHandle(forWritingTo: resultURL) {
            file.seekToEndOfFile()
            file.write(data)
            try? file.close()
        } else {
            try? data.write(to: resultURL, options: .atomic)
        }
    }

    /// Polls without blocking the main queue, so SDK completions that run on the main thread (and
    /// may hold the bridge lock) are still delivered while a harness waits for a state transition.
    private static func pollUntil(timeout: TimeInterval, condition: @escaping () -> Bool,
                                  completion: @escaping (Bool) -> Void) {
        let deadline = Date().addingTimeInterval(timeout)
        func tick() {
            if condition() { completion(true); return }
            if Date() >= deadline { completion(false); return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { tick() }
        }
        DispatchQueue.main.async { tick() }
    }

    static func runIfRequested(password: String?) {
        guard ProcessInfo.processInfo.environment["TILLFAILURE_PR1_DEPARTURE"] == "1" else { return }
        try? FileManager.default.removeItem(at: resultURL)
        guard let password, password.count >= 8 else { record("PR1_DEPARTURE configuration=FAIL"); return }
        guard let defaults = UserDefaults(suiteName: "tillfailure.pr1.departure.harness") else {
            record("PR1_DEPARTURE defaults=FAIL"); return
        }
        defaults.removePersistentDomain(forName: "tillfailure.pr1.departure.harness")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("TillFailurePR1DepartureHarness")
        try? FileManager.default.removeItem(at: root)
        let recovery = RecoveryPersistenceBridge(rootURL: root)
        let bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let client = IosProductIdentityClient(bridge: bridge, recoveryBridge: recovery)
        _ = bridge.signIn(email: "pr1-client@example.invalid", password: password, accountEpoch: 0) { signed in
            guard signed.failure == nil, bridge.debugCurrentUid == "pr1_client",
                  client.claimPinnedIdentity(uid: "pr1_client") else {
                record("PR1_DEPARTURE authAndRegistry=FAIL"); return
            }
            record("PR1_DEPARTURE authAndRegistry=\(client.hasProvenEmptyCriticalWork(uid: "pr1_client") ? "PASS" : "FAIL")")
            let pending = envelope(uid: "pr1_client", pending: true)
            _ = recovery.write(uid: "pr1_client", plaintext: pending)
            CleanDepartureCoordinator(port: client).depart(uid: "pr1_client", disposeAccountCallbacks: {}) { outcome in
                let blocked = outcome == .preflightblocked && bridge.debugCurrentUid == "pr1_client" &&
                    bridge.readDepartureMarker().uid == nil
                record("PR1_DEPARTURE pendingJournal=\(blocked ? "PASS" : "FAIL")")
                _ = recovery.write(uid: "pr1_client", plaintext: envelope(uid: "pr1_client", pending: false))
                bridge.disableNetwork { network in
                    guard network.failure == nil else { record("PR1_DEPARTURE stalledDrain=FAIL"); return }
                    _ = bridge.writeDocument(path: "spikeEcho/pr1-ios-pending", fields: ["value": "pending"], accountEpoch: 0) { _ in }
                    _ = client.drain(timeoutMillis: 300) { failure in
                        let stalled = failure?.code == .deadlineExceeded && bridge.debugCurrentUid == "pr1_client" &&
                            bridge.readDepartureMarker().uid == nil
                        record("PR1_DEPARTURE stalledDrain=\(stalled ? "PASS" : "FAIL")")
                        bridge.enableNetwork { _ in
                            runCleanupFailure(client: client, bridge: bridge, recovery: recovery, password: password)
                        }
                    }
                }
            }
        }
    }

    private static func runCleanupFailure(client: IosProductIdentityClient, bridge: FirebaseNativeBridge,
                                          recovery: RecoveryPersistenceBridge, password: String) {
        recovery.debugBeforeDelete = { _ in
            recovery.debugBeforeDelete = nil
            try? FileManager.default.removeItem(at: recovery.debugFileURL(uid: "pr1_client"))
        }
        CleanDepartureCoordinator(port: client).depart(uid: "pr1_client", disposeAccountCallbacks: {}) { outcome in
            let locked = outcome == .cleanuprequired && bridge.readDepartureMarker().uid == "pr1_client"
            record("PR1_DEPARTURE cleanupFailureLocks=\(locked ? "PASS" : "FAIL")")
            recovery.debugBeforeDelete = nil
            let restartedBridge = bridge.replacementBridge() as! FirebaseNativeBridge
            let restarted = IosProductIdentityClient(bridge: restartedBridge, recoveryBridge: recovery)
            let before = restartedBridge.readDepartureMarker()
            record("PR1_DEPARTURE recoveryBefore uid=\(before.uid ?? "nil") epoch=\(before.epoch) readable=\(before.readable) auth=\(restartedBridge.debugCurrentUid ?? "nil")")
            CleanDepartureCoordinator(port: restarted).recover(disposeAccountCallbacks: {}) { recovered, hadMarker in
                let okay = recovered == .clean && hadMarker.boolValue && restartedBridge.readDepartureMarker().uid == nil
                let after = restartedBridge.readDepartureMarker()
                record("PR1_DEPARTURE recoveryAfter outcome=\(recovered) had=\(hadMarker.boolValue) uid=\(after.uid ?? "nil") epoch=\(after.epoch) readable=\(after.readable)")
                record("PR1_DEPARTURE restartRecovery=\(okay ? "PASS" : "FAIL")")
                let b = restartedBridge.replacementBridge() as! FirebaseNativeBridge
                _ = b.signIn(email: "pr1-trainer@example.invalid", password: password, accountEpoch: 0) { result in
                    let bClient = IosProductIdentityClient(bridge: b, recoveryBridge: recovery)
                    guard result.failure == nil, bClient.claimPinnedIdentity(uid: "pr1_trainer") else {
                        record("PR1_DEPARTURE accountB=FAIL"); return
                    }
                    _ = b.getDocument(path: "users/pr1_client", accountEpoch: 0) { denied in
                        _ = b.getDocument(path: "users/pr1_trainer", accountEpoch: 0) { own in
                            let isolated = denied.failure?.code == "PERMISSION_DENIED" &&
                                own.document?.isFromCache == false && own.document?.exists == true
                            record("PR1_DEPARTURE accountB=\(isolated ? "PASS" : "FAIL")")
                        }
                    }
                }
            }
        }
    }

    private static func envelope(uid: String, pending: Bool) -> String {
        let journal: [[String: Any]] = pending ? [[
            "schemaVersion": 1, "uid": uid, "workspaceId": "pr1_workspace", "operationId": "pending",
            "payload": "test", "state": "PendingSync"
        ]] : []
        let object: [String: Any] = [
            "schemaVersion": 1, "uid": uid, "offlineAccessGrant": NSNull(), "downloadManifests": [],
            "workoutRecoverySnapshot": NSNull(), "mutationJournal": journal, "pendingUploads": [],
            "switchMarker": NSNull()
        ]
        let data = try! JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        return String(data: data, encoding: .utf8)!
    }

    static func runUnsupportedIfRequested(password: String?) {
        guard ProcessInfo.processInfo.environment["TILLFAILURE_PR1_UNSUPPORTED"] == "1" else { return }
        try? FileManager.default.removeItem(at: resultURL)
        guard let password, password.count >= 8,
              let defaults = UserDefaults(suiteName: "tillfailure.pr1.unsupported.harness") else {
            record("PR1_UNSUPPORTED configuration=FAIL"); exit(1)
        }
        defaults.removePersistentDomain(forName: "tillfailure.pr1.unsupported.harness")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("TillFailurePR1UnsupportedHarness")
        try? FileManager.default.removeItem(at: root)
        let recovery = RecoveryPersistenceBridge(rootURL: root)
        let bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let client = IosProductIdentityClient(bridge: bridge, recoveryBridge: recovery)
        let uid = "pr1_unsupported/uid"
        _ = bridge.signIn(email: "pr1-unsupported@example.invalid", password: password, accountEpoch: 0) { result in
            guard result.failure == nil, bridge.debugCurrentUid == uid,
                  client.claimPinnedIdentity(uid: uid), client.hasProvenEmptyCriticalWork(uid: uid),
                  client.freeze(uid: uid) else {
                record("PR1_UNSUPPORTED preflight=FAIL"); exit(1)
            }
            _ = client.drain(timeoutMillis: 5_000) { failure in
                guard failure == nil, client.persistMarker(uid: uid) else {
                    record("PR1_UNSUPPORTED marker=FAIL"); exit(1)
                }
                let resumedBridge = bridge.replacementBridge() as! FirebaseNativeBridge
                let resumed = IosProductIdentityClient(bridge: resumedBridge, recoveryBridge: recovery)
                guard resumedBridge.readDepartureMarker().uid == uid else {
                    record("PR1_UNSUPPORTED markerRead=FAIL"); exit(1)
                }
                CleanDepartureCoordinator(port: resumed).recover(disposeAccountCallbacks: {}) { outcome, hadMarker in
                    guard outcome == .clean, hadMarker.boolValue, resumedBridge.readDepartureMarker().uid == nil,
                          !resumedBridge.hasPinnedIdentity() else {
                        record("PR1_UNSUPPORTED recovery=FAIL"); exit(1)
                    }
                    record("PR1_UNSUPPORTED recovery=PASS")
                    verifyB(phase: 5, bridge: resumedBridge.replacementBridge() as! FirebaseNativeBridge,
                            recovery: recovery, password: password)
                }
            }
        }
    }

    static func runAnonymousIfRequested(password: String?) {
        guard ProcessInfo.processInfo.environment["TILLFAILURE_PR1_ANONYMOUS"] == "1" else { return }
        try? FileManager.default.removeItem(at: resultURL)
        guard let password, password.count >= 8,
              let defaults = UserDefaults(suiteName: "tillfailure.pr1.anonymous.harness") else {
            record("PR1_ANONYMOUS configuration=FAIL"); exit(1)
        }
        defaults.removePersistentDomain(forName: "tillfailure.pr1.anonymous.harness")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("TillFailurePR1AnonymousHarness")
        try? FileManager.default.removeItem(at: root)
        let recovery = RecoveryPersistenceBridge(rootURL: root)
        let bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let client = IosProductIdentityClient(bridge: bridge, recoveryBridge: recovery)
        bridge.signInAnonymously { result in
            guard case .success(let uid) = result, bridge.debugCurrentUid == uid,
                  client.claimPinnedIdentity(uid: uid), client.hasProvenEmptyCriticalWork(uid: uid) else {
                record("PR1_ANONYMOUS preflight=FAIL"); exit(1)
            }
            CleanDepartureCoordinator(port: client).depart(uid: uid, disposeAccountCallbacks: {}) { outcome in
                guard outcome == .clean, bridge.readDepartureMarker().uid == nil,
                      !bridge.hasPinnedIdentity(), recovery.read(uid: uid).payload == nil else {
                    record("PR1_ANONYMOUS departure=FAIL"); exit(1)
                }
                record("PR1_ANONYMOUS departure=PASS")
                let b = bridge.replacementBridge() as! FirebaseNativeBridge
                _ = b.signIn(email: "pr1-trainer@example.invalid", password: password, accountEpoch: 0) { auth in
                    let bClient = IosProductIdentityClient(bridge: b, recoveryBridge: recovery)
                    guard auth.failure == nil, bClient.claimPinnedIdentity(uid: "pr1_trainer") else {
                        record("PR1_ANONYMOUS accountB=FAIL auth"); exit(1)
                    }
                    _ = b.getDocument(path: "users/\(uid)", accountEpoch: 0) { denied in
                        _ = b.getDocument(path: "users/pr1_trainer", accountEpoch: 0) { own in
                            let isolated = denied.failure?.code == "PERMISSION_DENIED" &&
                                own.document?.isFromCache == false && own.document?.exists == true
                            record("PR1_ANONYMOUS accountB=\(isolated ? "PASS" : "FAIL")")
                            exit(isolated ? 0 : 1)
                        }
                    }
                }
            }
        }
    }

    static func runCatalogReturnIfRequested() {
        guard ProcessInfo.processInfo.environment["TILLFAILURE_PR1_CATALOG_RETURN"] == "1" else { return }
        try? FileManager.default.removeItem(at: resultURL)
        guard let defaults = UserDefaults(suiteName: "tillfailure.pr1.catalog-return.harness") else {
            record("PR1_CATALOG_RETURN configuration=FAIL"); exit(1)
        }
        defaults.removePersistentDomain(forName: "tillfailure.pr1.catalog-return.harness")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("TillFailurePR1CatalogReturnHarness")
        try? FileManager.default.removeItem(at: root)
        let recovery = RecoveryPersistenceBridge(rootURL: root)
        let originalBridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let client = IosProductIdentityClient(bridge: originalBridge, recoveryBridge: recovery)
        originalBridge.signInAnonymously { result in
            guard case .success(let uid) = result, client.claimPinnedIdentity(uid: uid) else {
                record("PR1_CATALOG_RETURN preflight=FAIL"); exit(1)
            }
            CleanDepartureCoordinator(port: client).depart(uid: uid, disposeAccountCallbacks: {}) { outcome in
                guard outcome == .clean, originalBridge.isRetired() else {
                    record("PR1_CATALOG_RETURN departure=FAIL"); exit(1)
                }
                _ = client.replacement()
                // MainViewController's supplier uses the original injected bridge's active successor
                // when the product gate is mounted again after the catalog is closed.
                let returnedClient = IosProductIdentityClient(
                    bridge: originalBridge.activeBridge(), recoveryBridge: recovery)
                guard !returnedClient.isRetired() else {
                    record("PR1_CATALOG_RETURN liveClient=FAIL"); exit(1)
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 10) {
                    record("PR1_CATALOG_RETURN session=FAIL timeout"); exit(1)
                }
                _ = returnedClient.observeSession(epoch: 0) { session in
                    let ready = session.uid == nil && !session.isAnonymous
                    record("PR1_CATALOG_RETURN session=\(ready ? "PASS" : "FAIL")")
                    exit(ready ? 0 : 1)
                }
            }
        }
    }

    static func runInterruptedPhaseIfRequested(password: String?) {
        let env = ProcessInfo.processInfo.environment
        let staging = env["TILLFAILURE_PR1_DEPARTURE_STAGE"]
        let resuming = env["TILLFAILURE_PR1_DEPARTURE_RESUME"]
        guard let phaseText = staging ?? resuming, let phase = Int(phaseText), (0...4).contains(phase) else { return }
        guard let password, password.count >= 8,
              let defaults = UserDefaults(suiteName: "tillfailure.pr1.departure.phase\(phase)") else {
            record("PR1_PHASE_\(phase) configuration=FAIL"); exit(1)
        }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("TillFailurePR1Phase\(phase)")
        if staging != nil {
            defaults.removePersistentDomain(forName: "tillfailure.pr1.departure.phase\(phase)")
            try? FileManager.default.removeItem(at: root)
        }
        let recovery = RecoveryPersistenceBridge(rootURL: root)
        let bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let client = IosProductIdentityClient(bridge: bridge, recoveryBridge: recovery)
        if staging != nil {
            _ = bridge.signIn(email: "pr1-client@example.invalid", password: password, accountEpoch: 0) { result in
                guard result.failure == nil, client.claimPinnedIdentity(uid: "pr1_client") else {
                    record("PR1_PHASE_\(phase) stage=FAIL auth"); exit(1)
                }
                if phase == 4 {
                    CleanDepartureCoordinator(port: client).depart(uid: "pr1_client", disposeAccountCallbacks: {}) { outcome in
                        guard outcome == .clean, bridge.readDepartureMarker().uid == nil else {
                            record("PR1_PHASE_4 clean=FAIL"); exit(1)
                        }
                        record("PR1_PHASE_4 clean=PASS")
                        let replacement = bridge.replacementBridge() as! FirebaseNativeBridge
                        let recreatedClient = IosProductIdentityClient(bridge: bridge, recoveryBridge: recovery)
                        let hostRecreation = !recreatedClient.isRetired() &&
                            (bridge.activeBridge() as? FirebaseNativeBridge) === replacement
                        record("PR1_PHASE_4 hostRecreation=\(hostRecreation ? "PASS" : "FAIL")")
                        guard hostRecreation else { exit(1) }
                        verifyB(phase: phase, bridge: replacement,
                                recovery: recovery, password: password)
                    }
                    return
                }
                guard client.freeze(uid: "pr1_client"), client.hasProvenEmptyCriticalWork(uid: "pr1_client") else {
                    record("PR1_PHASE_\(phase) stage=FAIL preflight"); exit(1)
                }
                _ = client.drain(timeoutMillis: 5_000) { failure in
                    guard failure == nil, client.persistMarker(uid: "pr1_client") else {
                        record("PR1_PHASE_\(phase) stage=FAIL marker"); exit(1)
                    }
                    if phase == 0 { record("PR1_PHASE_0 marker=PASS"); exit(0) }
                    bridge.fenceDepartureCallbacks()
                    var lateCallback = false
                    _ = bridge.getDocument(path: "users/pr1_client", accountEpoch: 0) { _ in lateCallback = true }
                    bridge.signOutProduct { signOut in
                        guard signOut.failure == nil else { record("PR1_PHASE_\(phase) stage=FAIL signOut"); exit(1) }
                        if phase == 1 {
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                                record("PR1_PHASE_1 signOut=PASS lateCallbacks=\(!lateCallback ? "PASS" : "FAIL")")
                                exit(lateCallback ? 1 : 0)
                            }
                            return
                        }
                        bridge.terminateAndClear { teardown in
                            guard teardown.failure == nil else { record("PR1_PHASE_\(phase) stage=FAIL teardown"); exit(1) }
                            if phase == 2 { record("PR1_PHASE_2 teardown=PASS"); exit(0) }
                            guard client.cleanupLocal(uid: "pr1_client") else {
                                record("PR1_PHASE_3 stage=FAIL local"); exit(1)
                            }
                            record("PR1_PHASE_3 localCleanup=PASS")
                            exit(0)
                        }
                    }
                }
            }
        } else {
            let before = bridge.readDepartureMarker()
            record("PR1_PHASE_\(phase) resumeBefore uid=\(before.uid ?? "nil") auth=\(bridge.debugCurrentUid ?? "nil")")
            CleanDepartureCoordinator(port: client).recover(disposeAccountCallbacks: {}) { outcome, hadMarker in
                guard outcome == .clean, hadMarker.boolValue, bridge.readDepartureMarker().uid == nil else {
                    record("PR1_PHASE_\(phase) recovery=FAIL"); exit(1)
                }
                record("PR1_PHASE_\(phase) recovery=PASS")
                verifyB(phase: phase, bridge: bridge.replacementBridge() as! FirebaseNativeBridge,
                        recovery: recovery, password: password)
            }
        }
    }

    /// Regression: a non-anonymous email/password session whose freshly checked Auth user is
    /// unverified stops at the explicit unverified gate before any account/workspace authorization.
    static func runUnverifiedEmailIfRequested(password: String?) {
        guard ProcessInfo.processInfo.environment["TILLFAILURE_PR1_UNVERIFIED"] == "1" else { return }
        try? FileManager.default.removeItem(at: resultURL)
        guard let password, password.count >= 8,
              let defaults = UserDefaults(suiteName: "tillfailure.pr1.unverified.harness") else {
            record("PR1_UNVERIFIED configuration=FAIL"); exit(1)
        }
        defaults.removePersistentDomain(forName: "tillfailure.pr1.unverified.harness")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("TillFailurePR1UnverifiedHarness")
        try? FileManager.default.removeItem(at: root)
        let recovery = RecoveryPersistenceBridge(rootURL: root)
        let bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let client = IosProductIdentityClient(bridge: bridge, recoveryBridge: recovery)
        _ = bridge.signIn(email: "pr1-unverified@example.invalid", password: password, accountEpoch: 0) { signed in
            guard signed.failure == nil, bridge.debugCurrentUid == "pr1_unverified" else {
                record("PR1_UNVERIFIED auth=FAIL"); exit(1)
            }
            record("PR1_UNVERIFIED auth=PASS")
            // The freshly refreshed session — not a cached observed flag and not the entered email —
            // carries the verification state.
            _ = client.refreshSession(epoch: 0) { refresh in
                guard refresh.failure == nil, let session = refresh.session else {
                    record("PR1_UNVERIFIED fresh=FAIL"); exit(1)
                }
                let fresh = session.email == "pr1-unverified@example.invalid" && !session.emailVerified
                record("PR1_UNVERIFIED fresh=\(fresh ? "PASS" : "FAIL")")
                let predicate = IdentityGateKt.requiresUnverifiedEmailGate(session: session)
                record("PR1_UNVERIFIED predicate=\(predicate ? "PASS" : "FAIL")")
                // The shared gate stops before account/workspace/membership authorization. Poll
                // without blocking the main queue so Auth/Firestore completions keep being delivered.
                let model = IdentityViewModel(initialClient: client)
                Self.pollUntil(timeout: 20, condition: {
                    model.currentState().status == GateStatus.unverifiedemail
                }) { reached in
                    let observed = model.currentState()
                    record("PR1_UNVERIFIED status=\(observed.status.name)")
                    let blocked = reached && observed.verifiedRole == nil
                    record("PR1_UNVERIFIED gate=\(blocked ? "PASS" : "FAIL")")
                    _ = bridge.getDocument(path: "workspaces/pr1_workspace", accountEpoch: 0) { workspace in
                        let workspaceDenied = workspace.failure?.code == "PERMISSION_DENIED"
                        _ = bridge.discoverMemberships(uid: "pr1_unverified", accountEpoch: 0) { discovery in
                            let discoveryDenied = discovery.failure?.code == "PERMISSION_DENIED"
                            record("PR1_UNVERIFIED rules=\(workspaceDenied && discoveryDenied ? "PASS" : "FAIL")")
                            exit(fresh && predicate && blocked && workspaceDenied && discoveryDenied ? 0 : 1)
                        }
                    }
                }
            }
        }
    }

    /// Regression: a stalled pre-marker departure stays owned across root recreation and completes
    /// exactly once, with account A/B isolation.
    static func runRecreatedRootIfRequested(password: String?) {
        guard ProcessInfo.processInfo.environment["TILLFAILURE_PR1_RECREATED_ROOT"] == "1" else { return }
        try? FileManager.default.removeItem(at: resultURL)
        guard let password, password.count >= 8,
              let defaults = UserDefaults(suiteName: "tillfailure.pr1.recreated-root.harness") else {
            record("PR1_RECREATED_ROOT configuration=FAIL"); exit(1)
        }
        defaults.removePersistentDomain(forName: "tillfailure.pr1.recreated-root.harness")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("TillFailurePR1RecreatedRootHarness")
        try? FileManager.default.removeItem(at: root)
        let recovery = RecoveryPersistenceBridge(rootURL: root)
        let bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let client = IosProductIdentityClient(bridge: bridge, recoveryBridge: recovery)
        _ = bridge.signIn(email: "pr1-client@example.invalid", password: password, accountEpoch: 0) { signed in
            guard signed.failure == nil, bridge.debugCurrentUid == "pr1_client",
                  client.claimPinnedIdentity(uid: "pr1_client") else {
                record("PR1_RECREATED_ROOT auth=FAIL"); exit(1)
            }
            record("PR1_RECREATED_ROOT auth=PASS")
            bridge.disableNetwork { network in
                guard network.failure == nil else { record("PR1_RECREATED_ROOT offline=FAIL"); exit(1) }
                _ = bridge.writeDocument(path: "spikeEcho/pr1-ios-root-recreation",
                                         fields: ["value": "pending"], accountEpoch: 0) { _ in }
                var ownerClean = false
                var ownerSettled = false
                let leased = client.ownership.lease(uid: "pr1_client") { outcome in
                    ownerClean = (outcome == .clean)
                    ownerSettled = true
                }
                guard leased else { record("PR1_RECREATED_ROOT lease=FAIL"); exit(1) }
                CleanDepartureCoordinator(port: client).depart(uid: "pr1_client", disposeAccountCallbacks: {}) { outcome in
                    client.ownership.settle(uid: "pr1_client", outcome: outcome)
                }
                // The pre-marker interval is visible: the account is frozen/draining, no marker yet.
                let preMarker = client.ownership.progress() == DepartureProgress.premarker &&
                    bridge.readDepartureMarker().uid == nil
                record("PR1_RECREATED_ROOT preMarker=\(preMarker ? "PASS" : "FAIL")")

                // Recreate the root: it resolves the same process-level bridge (hence the same owner),
                // joins the in-flight departure and cannot start a competing one.
                let recreated = IosProductIdentityClient(bridge: bridge.activeBridge(), recoveryBridge: recovery)
                var adoptedClean = false
                let joined = recreated.ownership.observeInFlight(onJoined: {}, onSettled: { outcome in
                    adoptedClean = (outcome == .clean)
                })
                let refused = !recreated.ownership.lease(uid: "pr1_client") { _ in }
                let visible = (recreated.ownership === client.ownership) && joined == "pr1_client" && refused
                record("PR1_RECREATED_ROOT visible=\(visible ? "PASS" : "FAIL")")

                bridge.enableNetwork { _ in
                    Self.pollUntil(timeout: 30, condition: {
                        ownerSettled && client.ownership.progress() == DepartureProgress.idle
                    }) { _ in
                        let recovered = ownerClean && adoptedClean && bridge.readDepartureMarker().uid == nil
                        record("PR1_RECREATED_ROOT recovery=\(recovered ? "PASS" : "FAIL")")
                        let b = bridge.replacementBridge() as! FirebaseNativeBridge
                        _ = b.signIn(email: "pr1-trainer@example.invalid", password: password, accountEpoch: 0) { auth in
                            let bClient = IosProductIdentityClient(bridge: b, recoveryBridge: recovery)
                            guard auth.failure == nil, bClient.claimPinnedIdentity(uid: "pr1_trainer") else {
                                record("PR1_RECREATED_ROOT accountB=FAIL auth"); exit(1)
                            }
                            _ = b.getDocument(path: "users/pr1_client", accountEpoch: 0) { denied in
                                _ = b.getDocument(path: "users/pr1_trainer", accountEpoch: 0) { own in
                                    let isolated = denied.failure?.code == "PERMISSION_DENIED" &&
                                        own.document?.isFromCache == false && own.document?.exists == true
                                    record("PR1_RECREATED_ROOT accountB=\(isolated ? "PASS" : "FAIL")")
                                    exit(preMarker && visible && recovered && isolated ? 0 : 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /// Regression: an out-of-band email verification is observed on Recheck without a new sign-in.
    static func runRecheckVerificationIfRequested(password: String?) {
        guard ProcessInfo.processInfo.environment["TILLFAILURE_PR1_RECHECK"] == "1" else { return }
        try? FileManager.default.removeItem(at: resultURL)
        guard let password, password.count >= 8,
              let defaults = UserDefaults(suiteName: "tillfailure.pr1.recheck.harness") else {
            record("PR1_RECHECK configuration=FAIL"); exit(1)
        }
        defaults.removePersistentDomain(forName: "tillfailure.pr1.recheck.harness")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("TillFailurePR1RecheckHarness")
        try? FileManager.default.removeItem(at: root)
        let recovery = RecoveryPersistenceBridge(rootURL: root)
        let bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let client = IosProductIdentityClient(bridge: bridge, recoveryBridge: recovery)
        guard setEmailVerified(uid: "pr1_unverified", verified: false) else {
            record("PR1_RECHECK fixture=FAIL"); exit(1)
        }
        _ = bridge.signIn(email: "pr1-unverified@example.invalid", password: password, accountEpoch: 0) { signed in
            guard signed.failure == nil, bridge.debugCurrentUid == "pr1_unverified" else {
                record("PR1_RECHECK auth=FAIL"); exit(1)
            }
            _ = client.refreshSession(epoch: 0) { before in
                let stale = before.failure == nil && before.session?.emailVerified == false
                record("PR1_RECHECK before=\(stale ? "PASS" : "FAIL")")
                // The account is verified out of band while this client stays signed in.
                let updated = setEmailVerified(uid: "pr1_unverified", verified: true)
                record("PR1_RECHECK outOfBand=\(updated ? "PASS" : "FAIL")")
                _ = client.refreshSession(epoch: 0) { after in
                    let fresh = after.failure == nil && after.session?.emailVerified == true
                    record("PR1_RECHECK after=\(fresh ? "PASS" : "FAIL")")
                    _ = bridge.discoverMemberships(uid: "pr1_unverified", accountEpoch: 0) { discovery in
                        let directory = discovery.failure == nil &&
                            discovery.documents?.map(\.path) == ["users/pr1_unverified/membershipRefs/pr1_workspace"]
                        record("PR1_RECHECK directory=\(directory ? "PASS" : "FAIL")")
                        _ = setEmailVerified(uid: "pr1_unverified", verified: false)
                        exit(stale && updated && fresh && directory ? 0 : 1)
                    }
                }
            }
        }
    }

    /// Applies an out-of-band Admin email-verification change on the local Auth emulator.
    @discardableResult
    private static func setEmailVerified(uid: String, verified: Bool) -> Bool {
        let port = Int(ProcessInfo.processInfo.environment["TF_AUTH_EMULATOR_PORT"] ?? "") ?? 9099
        let endpoint = "http://127.0.0.1:\(port)/identitytoolkit.googleapis.com/v1/projects/demo-tillfailure-m3/accounts:update"
        guard let url = URL(string: endpoint) else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer owner", forHTTPHeaderField: "Authorization")
        request.httpBody = try? JSONSerialization.data(withJSONObject: ["localId": uid, "emailVerified": verified])
        var ok = false
        let semaphore = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { _, response, _ in
            if let http = response as? HTTPURLResponse { ok = (200...299).contains(http.statusCode) }
            semaphore.signal()
        }.resume()
        _ = semaphore.wait(timeout: .now() + 15)
        return ok
    }

    private static func verifyB(phase: Int, bridge: FirebaseNativeBridge,
                                recovery: RecoveryPersistenceBridge, password: String) {
        _ = bridge.signIn(email: "pr1-trainer@example.invalid", password: password, accountEpoch: 0) { result in
            let client = IosProductIdentityClient(bridge: bridge, recoveryBridge: recovery)
            guard result.failure == nil, client.claimPinnedIdentity(uid: "pr1_trainer") else {
                record("PR1_PHASE_\(phase) accountB=FAIL auth"); exit(1)
            }
            _ = bridge.getDocument(path: "users/pr1_client", accountEpoch: 0) { denied in
                _ = bridge.getDocument(path: "users/pr1_trainer", accountEpoch: 0) { own in
                    let isolated = denied.failure?.code == "PERMISSION_DENIED" && own.document?.isFromCache == false &&
                        own.document?.exists == true
                    record("PR1_PHASE_\(phase) accountB=\(isolated ? "PASS" : "FAIL")")
                    exit(isolated ? 0 : 1)
                }
            }
        }
    }
}
#endif
