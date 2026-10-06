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
