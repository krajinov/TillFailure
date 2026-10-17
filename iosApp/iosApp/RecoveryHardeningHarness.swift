import Foundation
import Darwin
import Shared

#if DEBUG
/// Explicit fixture injection on PR 5's isolated demo services. No repair or discard product API.
enum RecoveryHardeningHarness {
    private static let uid = "pr5_ios_native"
    private static let wid = "pr5_ios_workspace"
    private static var bridge: FirebaseNativeBridge!
    private static var recovery: RecoveryPersistenceBridge!
    private static var client: IosProductIdentityClient!
    private static var root: NativeOnboardingModels!
    private static var password = ""
    private static func check(_ ok: Bool, _ label: String) {
        print("PR5_IOS \(label)=\(ok ? "PASS" : "FAIL")"); fflush(stdout)
        if !ok { exit(1) }
    }
    private static func poll(_ status: GateStatus, then: @escaping () -> Void) {
        let deadline = Date().addingTimeInterval(40)
        func tick() {
            if root.identity.currentState().status == status { then(); return }
            if Date() > deadline { check(false, "gate-timeout-\(root.identity.currentState().status)"); return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05, execute: tick)
        }
        tick()
    }
    private static func command() -> InvitationRequest {
        InvitationRequest(action: "complete-onboarding", workspaceId: wid, invitationId: nil, email: nil, token: nil,
            expectedVersion: nil, idempotencyKey: "pr5-original", onboarding: OnboardingCompletion(displayName: "Recovery Client", locale: "en",
                timeZoneId: "UTC", acknowledged: true, expectedAccountRevision: 0, expectedOnboardingRevision: 0))
    }
    static func runIfRequested() {
        let env = ProcessInfo.processInfo.environment
        guard let phase = env["TF_PR5_NATIVE"],
              env["TF_AUTH_EMULATOR_PORT"] == "39099", env["TF_FIRESTORE_EMULATOR_PORT"] == "38080",
              env["TF_FUNCTIONS_EMULATOR_PORT"] == "35001",
              let defaults = UserDefaults(suiteName: phase == "guards" || phase == "frozen" ? "tillfailure.pr5.final-review-\(phase)" : "tillfailure.pr5.verified") else { return }
        bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let path = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent(phase == "guards" || phase == "frozen" ? "TillFailurePR5FinalReview-\(phase)" : "TillFailurePR5Verified")
        recovery = RecoveryPersistenceBridge(rootURL: path)
        client = IosProductIdentityClient(bridge: bridge, recoveryBridge: recovery)
        if phase == "guards" { checkNativeGuards(); return }
        guard let supplied = env["TF_PR5_PASSWORD"] else { return }
        password = supplied
        if phase == "frozen" { checkFrozenAccount(); return }
        if phase == "suspension-restore" {
            check(bridge.debugCurrentUid == "pr5_ios_large", "suspension-owner-restored")
            root = NativeOnboardingModels(client: client)
            poll(.accesslost) {
                check(root.identity.currentState().verifiedRole == nil, "suspension-server-revalidation-denied")
                root.identity.onEvent(event: IdentityEventSignOut.shared)
                poll(.signedout) { check(true, "suspension-clean-departure"); root.clear(); exit(0) }
            }
            return
        }
        if phase == "suspension" {
            _ = client.signIn(email: "pr5-ios-large@example.invalid", password: password, epoch: 0) { failure in
                check(failure == nil, "suspension-sign-in")
                root = NativeOnboardingModels(client: client)
                poll(.onboarding) {
                    patch("workspaces/\(wid)", "status", "suspended") {
                        poll(.accesslost) {
                            check(root.identity.currentState().verifiedRole == nil, "suspension-closes-root")
                            root.identity.onEvent(event: IdentityEventSignOut.shared)
                            poll(.signedout) { check(true, "suspension-clean-departure"); root.clear(); exit(0) }
                        }
                    }
                }
            }
            return
        }
        if phase == "restore" {
            check(bridge.debugCurrentUid == "pr5_ios_ui", "process-auth-restored")
            check(client.invitations.pending(uid: "pr5_ios_ui")?.idempotencyKey == "pr5-original", "process-original-restored")
            check(!client.hasProvenEmptyCriticalWork(uid: "pr5_ios_ui"), "process-departure-blocked")
            root = NativeOnboardingModels(client: client)
            poll(.clienthome) {
                _ = client.invitations.execute(uid: "pr5_ios_ui", request: command()) { outcome in
                    check(outcome.reply?.replayed?.boolValue == true, "process-original-receipt")
                    root.identity.onEvent(event: IdentityEventSignOut.shared)
                    poll(.signedout) { check(true, "process-clean-departure"); root.clear(); exit(0) }
                }
            }
            return
        }
        _ = client.signIn(email: phase == "start" ? "pr5-ios-ui@example.invalid" : "pr5-ios-native@example.invalid", password: password, epoch: 0) { failure in
            check(failure == nil && client.claimPinnedIdentity(uid: phase == "start" ? "pr5_ios_ui" : uid), "sign-in")
            root = NativeOnboardingModels(client: client)
            if phase == "start" {
                poll(.onboarding) {
                    bridge.debugBeforeInvitationCompletion = { _ in
                        check(client.invitations.pending(uid: "pr5_ios_ui")?.idempotencyKey == "pr5-original", "process-pending-durable")
                        print("PR5_IOS RESTART_READY"); fflush(stdout)
                    }
                    _ = client.invitations.execute(uid: "pr5_ios_ui", request: command()) { _ in check(false, "withheld-callback") }
                }
            } else { poll(.onboarding) { revokeInFlight() } }
        }
    }
    private static func checkNativeGuards() {
        let foreignUid = "pr5_final_review_foreign"
        check(bridge.debugCurrentUid == nil, "guards-no-auth-owner")
        check(!client.claimPinnedIdentity(uid: foreignUid), "guards-no-foreign-initialization")
        client.invitations.saveWorkspacePreference(uid: foreignUid, workspaceId: wid)
        check(recovery.read(uid: foreignUid).payload == nil && !client.hasPinnedIdentity(), "guards-no-foreign-storage")
        var invoked = false
        check(!bridge.withWritableAccount(uid: foreignUid) { invoked = true; return KotlinBoolean(bool: true) } && !invoked,
              "guards-native-writable-check")
        let before = bridge.debugCancellationCount
        var callbacks = 0
        let token = bridge.observeConnectivity { _ in callbacks += 1 }
        check(bridge.debugCancellationCount == before + 1, "guards-monitor-registered")
        bridge.cancel(token: token); bridge.cancel(token: token)
        check(bridge.debugCancellationCount == before, "guards-monitor-disposed-once")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            check(callbacks == 0, "guards-queued-connectivity-fenced")
            client.retireFirestore { failure in
                check(failure == nil, "guards-retired")
                check(!client.claimPinnedIdentity(uid: foreignUid), "guards-retired-initialization-refused")
                client.invitations.saveWorkspacePreference(uid: foreignUid, workspaceId: wid)
                check(recovery.read(uid: foreignUid).payload == nil && !client.hasPinnedIdentity(), "guards-retired-storage-absent")
                let retired = bridge.observeConnectivity { _ in callbacks += 1 }
                check(bridge.debugCancellationCount == 0, "guards-retired-monitor-refused")
                bridge.cancel(token: retired)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    check(callbacks == 0, "guards-retired-callbacks-absent")
                    exit(0)
                }
            }
        }
    }
    private static func checkFrozenAccount() {
        let owner = "pr5_final_review_ios"
        func inspect() {
            check(client.claimPinnedIdentity(uid: owner), "frozen-owner-initialized")
            bridge.debugBeforeInvitationTransport = { _ in } // No remote mutation is issued.
            _ = client.invitations.execute(uid: owner, request: command()) { _ in check(false, "frozen-withheld-transport") }
            check(client.invitations.pending(uid: owner)?.idempotencyKey == "pr5-original", "frozen-original-prepared")
            let before = try! recovery.debugRawData(uid: owner)
            check(client.freeze(uid: owner), "frozen-account")
            let recreated = IosProductIdentityClient(bridge: bridge, recoveryBridge: recovery)
            recreated.invitations.saveWorkspacePreference(uid: owner, workspaceId: "other")
            check(!recreated.claimPinnedIdentity(uid: owner) && !recreated.claimPinnedIdentity(uid: "foreign"), "frozen-initialization-refused")
            check((try! recovery.debugRawData(uid: owner)) == before, "frozen-bytes-preserved")
            check(recreated.invitations.pending(uid: owner)?.idempotencyKey == "pr5-original", "frozen-original-retained")
            client.unfreeze(uid: owner)
            recreated.invitations.saveWorkspacePreference(uid: owner, workspaceId: wid)
            check(recreated.invitations.workspacePreference(uid: owner) == wid, "unfrozen-preference-saved")
            check(client.invitations.pending(uid: owner)?.idempotencyKey == "pr5-original" && !client.hasProvenEmptyCriticalWork(uid: owner),
                  "unfrozen-command-still-blocks-departure")
            // Retain the protected fixture and owner; no cleanup/discard or transport is performed.
            exit(0)
        }
        if bridge.debugCurrentUid == owner { inspect() }
        else {
            _ = client.signIn(email: "pr5-final-review-ios@example.invalid", password: password, epoch: 0) { failure in
                check(failure == nil, "frozen-sign-in")
                inspect()
            }
        }
    }
    private static func revokeInFlight() {
        bridge.debugBeforeInvitationTransport = { transport in
            check(client.invitations.pending(uid: uid)?.idempotencyKey == "pr5-original", "prepared-before-transport")
            patch("workspaces/\(wid)/memberships/\(uid)", "status", "revoked") {
                poll(.lockedrecovery) {
                    check(root.identity.currentState().verifiedRole == nil, "revocation-closes-presentation")
                    transport()
                }
            }
        }
        _ = client.invitations.execute(uid: uid, request: command()) { outcome in
            check(outcome.error != nil && client.invitations.pending(uid: uid) != nil, "denied-command-retained")
            root.identity.onEvent(event: IdentityEventSignOut.shared)
            poll(.lockedrecovery) {
                check(bridge.debugCurrentUid == uid, "pending-blocks-departure")
                root.clear(); root = NativeOnboardingModels(client: client)
                poll(.lockedrecovery) {
                    check(client.invitations.pending(uid: uid)?.idempotencyKey == "pr5-original", "recreated-root-retains-original")
                    patch("workspaces/\(wid)/memberships/\(uid)", "status", "active") {
                        bridge.debugBeforeInvitationTransport = nil
                        root.identity.onEvent(event: IdentityEventRetry.shared)
                        poll(.onboarding) {
                            _ = client.invitations.execute(uid: uid, request: command()) { recovered in
                                check(recovered.reply?.status == "complete" && client.invitations.pending(uid: uid) == nil, "original-resolves")
                                root.identity.onEvent(event: IdentityEventRetry.shared)
                                poll(.clienthome) { finishLifecycle() }
                            }
                        }
                    }
                }
            }
        }
    }
    private static func finishLifecycle() {
        root.identity.onEvent(event: IdentityEventSuspend.shared)
        check(root.identity.currentState().verifiedRole == nil, "background-closes-root")
        root.identity.onEvent(event: IdentityEventForeground.shared)
        poll(.clienthome) {
            check(true, "foreground-revalidates")
            patch("users/\(uid)", "accountStatus", "disabled") {
                poll(.accesslost) {
                    check(root.identity.currentState().verifiedRole == nil, "account-disablement-closes-root")
                    root.identity.onEvent(event: IdentityEventSignOut.shared)
                    poll(.signedout) { accountB() }
                }
            }
        }
    }
    private static func accountB() {
        check(true, "ordered-clean-departure")
        let b = root.identity.activeClient()
        _ = b.signIn(email: "pr5-ios-trainer@example.invalid", password: password, epoch: 0) { failure in
            check(failure == nil, "account-b-sign-in")
            poll(.trainerhome) {
                check(root.identity.currentState().accountUid == "pr5_ios_trainer", "account-b-scope")
                _ = b.getDocument(path: "users/\(uid)", epoch: 0) { result in
                    check(result.failure?.code == .permissionDenied, "account-a-denied-to-b")
                    root.identity.onEvent(event: IdentityEventSignOut.shared)
                    poll(.signedout) { damagedStorage(); root.clear(); exit(0) }
                }
            }
        }
    }
    private static func damagedStorage() {
        let damaged = "pr5_damaged_fixture"
        let raw = "{\"schemaVersion\":2,\"uid\":\"\(damaged)\"}"
        check(recovery.write(uid: damaged, plaintext: raw).failureCode == nil, "damage-fixture-written")
        let before = try! recovery.debugRawData(uid: damaged)
        check(client.invitations.recoveryState(uid: damaged) == .storagelocked, "incompatible-storage-locked")
        check(!client.cleanupLocal(uid: damaged), "incompatible-storage-not-cleaned")
        check((try! recovery.debugRawData(uid: damaged)) == before, "damaged-bytes-preserved")
    }
    private static func patch(_ path: String, _ field: String, _ value: String, then: @escaping () -> Void) {
        var request = URLRequest(url: URL(string: "http://127.0.0.1:38080/v1/projects/demo-tillfailure-m3/databases/(default)/documents/\(path)?updateMask.fieldPaths=\(field)")!)
        request.httpMethod = "PATCH"; request.setValue("Bearer owner", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try! JSONSerialization.data(withJSONObject: ["fields": [field: ["stringValue": value]]])
        URLSession.shared.dataTask(with: request) { _, response, error in
            DispatchQueue.main.async {
                guard error == nil else { check(false, "fixture-transport"); return }
                let status = (response as? HTTPURLResponse)?.statusCode
                var read = URLRequest(url: URL(string: "http://127.0.0.1:38080/v1/projects/demo-tillfailure-m3/databases/(default)/documents/\(path)")!)
                read.setValue("Bearer owner", forHTTPHeaderField: "Authorization")
                URLSession.shared.dataTask(with: read) { data, readResponse, readError in
                    DispatchQueue.main.async {
                        let body = data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
                        let fields = body?["fields"] as? [String: [String: Any]]
                        check(readError == nil && (readResponse as? HTTPURLResponse)?.statusCode == 200 && fields?[field]?["stringValue"] as? String == value,
                              status == 200 ? "fixture-lifecycle-write" : "fixture-postcommit-watch-error-confirmed")
                        then()
                    }
                }.resume()
            }
        }.resume()
    }
}
#endif
