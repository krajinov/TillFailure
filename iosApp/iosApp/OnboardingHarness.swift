import Foundation
import Darwin
import Shared

#if DEBUG
enum OnboardingHarness {
    private static var client: IosProductIdentityClient!
    private static var root: NativeOnboardingModels!
    private static var ledger: InvitationLedger!
    private static func check(_ ok: Bool, _ label: String) {
        print("PR4_IOS \(label)=\(ok ? "PASS" : "FAIL")"); fflush(stdout)
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
    static func runIfRequested() {
        guard ProcessInfo.processInfo.environment["TF_PR4_NATIVE"] == "1",
              let password = ProcessInfo.processInfo.environment["TF_PR4_PASSWORD"],
              let defaults = UserDefaults(suiteName: "tillfailure.pr4.native") else { return }
        let bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let path = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TillFailurePR4Native")
        let recovery = RecoveryPersistenceBridge(rootURL: path)
        client = IosProductIdentityClient(bridge: bridge, recoveryBridge: recovery)
        ledger = InvitationLedger(store: IosAtomicFilePersistence(bridge: recovery))
        if ProcessInfo.processInfo.environment["TF_PR4_RESTART_PHASE"] == "restore" {
            root = NativeOnboardingModels(client: client)
            poll(.clienthome) {
                check(root.identity.currentState().accountUid == "pr4_ios_native", "cold-start-auth-uid-restored")
                check(ledger.pending(uid: "pr4_ios_native")?.idempotencyKey == "native-complete", "cold-start-original-key-restored")
                check(!client.hasProvenEmptyCriticalWork(uid: "pr4_ios_native"), "cold-start-departure-still-protected")
                _ = client.invitations.execute(uid: "pr4_ios_native", request: completionCommand()) { replay in
                    check(replay.reply?.replayed?.boolValue == true && ledger.pending(uid: "pr4_ios_native") == nil, "cold-start-original-receipt-reconciled")
                    root.identity.onEvent(event: IdentityEventSignOut.shared)
                    poll(.signedout) { check(true, "cold-start-clean-sign-out"); root.clear(); exit(0) }
                }
            }
            return
        }
        _ = client.signIn(email: "pr4-ios-native@example.invalid", password: password, epoch: 0) { failure in
            check(failure == nil, "native-sign-in")
            root = NativeOnboardingModels(client: client)
            if ProcessInfo.processInfo.environment["TF_PR4_RESTART_PHASE"] == "start" {
                poll(.clienthome) {
                    check(ledger.prepare(uid: "pr4_ios_native", request: completionCommand()), "restart-original-command-persisted")
                    check(!client.hasProvenEmptyCriticalWork(uid: "pr4_ios_native"), "restart-pending-work-protected")
                    print("PR4_IOS RESTART_READY"); fflush(stdout)
                }
            } else { poll(.onboarding) { complete() } }
        }
    }
    private static func completionCommand() -> InvitationRequest {
        return InvitationRequest(action: "complete-onboarding", workspaceId: "pr4_ios_workspace", invitationId: nil,
            email: nil, token: nil, expectedVersion: nil, idempotencyKey: "native-complete",
            onboarding: OnboardingCompletion(displayName: "Apple Client", locale: "en", timeZoneId: "UTC", acknowledged: true,
                expectedAccountRevision: 0, expectedOnboardingRevision: 0))
    }
    private static func complete() {
        let uid = "pr4_ios_native"
        let command = completionCommand()
        check(ledger.prepare(uid: uid, request: command), "original-command-protected")
        check(!client.hasProvenEmptyCriticalWork(uid: uid), "pending-work-not-clean")
        root.identity.onEvent(event: IdentityEventSignOut.shared)
        poll(.departureblocked) {
            check(root.identity.currentState().accountUid == uid, "pending-departure-blocked")
            root.clear(); root = NativeOnboardingModels(client: client)
            poll(.onboarding) {
                root.refresh()
                check(root.onboarding.currentState().pending?.idempotencyKey == "native-complete", "root-restores-original-key")
                _ = client.invitations.execute(uid: uid, request: command) { outcome in
                    check(outcome.error == nil && outcome.reply?.status == "complete", "native-completion")
                    check(ledger.pending(uid: uid) == nil && client.hasProvenEmptyCriticalWork(uid: uid), "receipt-settles-ledger")
                    _ = client.invitations.execute(uid: uid, request: command) { replay in
                        check(replay.reply?.replayed?.boolValue == true, "matching-replay")
                        root.identity.onEvent(event: IdentityEventRetry.shared)
                        poll(.clienthome) {
                            check(root.identity.currentState().displayName == "Apple Client", "global-basics-read")
                            root.clear(); root = NativeOnboardingModels(client: client)
                            poll(.clienthome) {
                                check(true, "recreated-root-restores-client-home")
                                root.identity.onEvent(event: IdentityEventSuspend.shared)
                                check(root.identity.currentState().status == .loading, "background-closes-root")
                                root.identity.onEvent(event: IdentityEventRetry.shared)
                                poll(.clienthome) {
                                    check(true, "foreground-revalidates-completion")
                                    root.identity.onEvent(event: IdentityEventSignOut.shared)
                                    poll(.signedout) { check(true, "ordered-clean-sign-out"); root.clear(); exit(0) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
#endif
