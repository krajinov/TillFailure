import Foundation
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Darwin
import Shared

#if DEBUG
/// Staged actual-process restart and delayed real SDK completion probes. Links are reopened from
/// the authenticated loopback fixture channel and never written to a file, launch argument or log.
enum InvitationLifecycleHarness {
    private static var bridge: FirebaseNativeBridge!
    private static var client: IosProductIdentityClient!
    private static var models: NativeInvitationModels!
    private static var originalModels: NativeInvitationModels?
    private static var issueHeld: (() -> Void)?
    private static var completionHeld: (() -> Void)?
    private static var oldState: InvitationState?
    private static var pending: PendingInvitationCommand?
    private static var password = ""
    private static func check(_ value: Bool, _ label: String) {
        print("PR3_IOS_LIFECYCLE \(label)=\(value ? "PASS" : "FAIL")")
        fflush(stdout)
        if !value { exit(1) }
    }
    private static func poll(_ condition: @escaping () -> Bool, then: @escaping () -> Void) {
        let deadline = Date().addingTimeInterval(35)
        func tick() {
            if condition() { then(); return }
            if Date() >= deadline { check(false, "condition-timeout"); return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.03, execute: tick)
        }
        tick()
    }
    private static var auth: Auth { Auth.auth(app: FirebaseApp.app(name: bridge.debugAppName)!) }
    private static var firestore: Firestore { Firestore.firestore(app: FirebaseApp.app(name: bridge.debugAppName)!) }
    private static func reopen(_ kind: String, then: @escaping (String) -> Void) {
        auth.currentUser!.getIDTokenForcingRefresh(true) { token, error in
            guard error == nil, let token else { check(false, "fixture-auth"); return }
            var request = URLRequest(url: URL(string: "http://127.0.0.1:9693/fixture/ios/\(kind)")!)
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            URLSession.shared.dataTask(with: request) { data, response, error in
                DispatchQueue.main.async {
                    guard error == nil, (response as? HTTPURLResponse)?.statusCode == 200, let data, data.count < 1024,
                          let body = try? JSONSerialization.jsonObject(with: data) as? [String: String], let value = body["entry"] else {
                        check(false, "fixture-delivery"); return
                    }
                    then(value)
                }
            }.resume()
        }
    }
    private static func signIn(_ email: String, then: @escaping () -> Void) {
        _ = client.signIn(email: email, password: password, epoch: 0) { failure in
            check(failure == nil, "sign-in")
            models = NativeInvitationModels(client: client)
            poll({ models.identity.currentState().status == .noaccount }) { models.synchronize(); then() }
        }
    }
    private static func preview(_ entry: String, then: @escaping () -> Void) {
        models.invitation.onEvent(event: InvitationEventEntry(value: entry))
        models.invitation.onEvent(event: InvitationEventOpen.shared)
        poll({ models.invitation.currentState().workspaceName != nil && !models.invitation.currentState().busy }, then: then)
    }
    private static func blocked(then: @escaping () -> Void) {
        let uid = models.identity.currentState().accountUid
        models.identity.onEvent(event: IdentityEventSignOut.shared)
        poll({ models.identity.currentState().status == .departureblocked }) {
            _ = client.signIn(email: "pr3-ios-trainer@example.invalid", password: password, epoch: 0) { failure in
                check(failure?.code == .failedPrecondition && bridge.debugCurrentUid == uid, "critical-work-blocks-account-B")
                then()
            }
        }
    }
    static func runIfRequested() {
        guard let phase = ProcessInfo.processInfo.environment["TF_PR3_LIFECYCLE"], ["stage", "resume", "late"].contains(phase),
              let supplied = ProcessInfo.processInfo.environment["TF_PR3_TEST_PASSWORD"],
              let defaults = UserDefaults(suiteName: "tillfailure.pr3.finalize.harness") else { return }
        password = supplied
        bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TillFailurePR3Finalize")
        client = IosProductIdentityClient(bridge: bridge, recoveryBridge: RecoveryPersistenceBridge(rootURL: root))
        if phase == "stage" { stage() } else if phase == "resume" { resume() } else { late() }
    }
    private static func stage() {
        signIn("pr3-ios-client@example.invalid") {
            reopen("restart") { entry in
                preview(entry) {
                    bridge.debugBeforeInvitationCompletion = { completion in completionHeld = completion }
                    models.invitation.onEvent(event: InvitationEventAccept.shared)
                    models.invitation.onEvent(event: InvitationEventConfirm.shared)
                    poll({ completionHeld != nil }) {
                        check(client.invitations.pending(uid: "pr3_ios_client")?.action == "accept", "protected-command-before-delivery")
                        check(models.identity.currentState().verifiedRole == nil, "held-response-no-role")
                        blocked {
                            check(true, "RESTART_RESPONSE_HELD")
                            // The host now terminates this actual app process. Do not release the callback.
                        }
                    }
                }
            }
        }
    }
    private static func resume() {
        check(bridge.debugCurrentUid == "pr3_ios_client", "restored-auth-uid")
        check(client.invitations.memory.link == nil, "restart-has-no-local-token")
        pending = client.invitations.pending(uid: "pr3_ios_client")
        check(pending != nil, "restored-protected-command")
        firestore.disableNetwork { error in
            check(error == nil, "offline-firestore")
            models = NativeInvitationModels(client: client)
            poll({ models.identity.currentState().status == .connecttoverify }) {
                check(models.identity.currentState().verifiedRole == nil, "local-metadata-cannot-grant-role")
                models.synchronize()
                blocked {
                    reopen("restart") { entry in
                        models.invitation.onEvent(event: InvitationEventEntry(value: entry))
                        models.invitation.onEvent(event: InvitationEventOpen.shared)
                        check(models.invitation.currentState().pending == pending, "original-command-survives-reopen")
                        firestore.enableNetwork { error in
                            check(error == nil, "online-firestore")
                            models.identity.onEvent(event: IdentityEventRetry.shared)
                            poll({ models.identity.currentState().status == .membershipverifiedfeaturepending }) {
                                models.synchronize()
                                bridge.debugBeforeInvitationTransport = { issue in
                                    check(client.invitations.pending(uid: "pr3_ios_client") == pending, "same-caller-key-before-retry")
                                    issue()
                                }
                                models.invitation.onEvent(event: InvitationEventRetryCommand.shared)
                                poll({ !models.invitation.currentState().busy && client.invitations.pending(uid: "pr3_ios_client") == nil }) {
                                    check(models.invitation.currentState().message.hasPrefix("Invitation accepted"), "receipt-retry-confirmed")
                                    check(client.invitations.memory.link == nil, "resolved-token-cleared")
                                    models.identity.onEvent(event: IdentityEventRetry.shared)
                                    poll({ models.identity.currentState().verifiedRole == "client" }) {
                                        check(true, "fresh-server-role-after-resolution")
                                        models.identity.onEvent(event: IdentityEventSignOut.shared)
                                        poll({ models.identity.currentState().status == .signedout }) {
                                            models.clear(); check(true, "RESTART_REPLAY_AND_FRESH_GATE"); exit(0)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private static func late() {
        signIn("pr3-ios-late@example.invalid") {
            reopen("late") { entry in
                preview(entry) {
                    bridge.debugBeforeInvitationTransport = { issue in issueHeld = issue }
                    bridge.debugBeforeInvitationCompletion = { completion in completionHeld = completion }
                    models.invitation.onEvent(event: InvitationEventAccept.shared)
                    models.invitation.onEvent(event: InvitationEventConfirm.shared)
                    poll({ issueHeld != nil }) {
                        originalModels = models
                        oldState = models.invitation.currentState()
                        pending = client.invitations.pending(uid: "pr3_ios_late")
                        models.clear()
                        models = NativeInvitationModels(client: client)
                        poll({ models.identity.currentState().status == .noaccount }) {
                            models.synchronize()
                            models.invitation.onEvent(event: InvitationEventRetryCommand.shared)
                            check(client.invitations.pending(uid: "pr3_ios_late") == pending, "overlapping-retry-does-not-settle-command")
                            blocked {
                                issueHeld!()
                                poll({ completionHeld != nil }) {
                                    check(originalModels!.invitation.currentState() == oldState, "cleared-root-unchanged-before-delivery")
                                    completionHeld!()
                                    poll({ client.invitations.pending(uid: "pr3_ios_late") == nil }) {
                                        check(originalModels!.invitation.currentState() == oldState, "cleared-root-unchanged-after-delivery")
                                        check(models.invitation.currentState().pending == nil && models.invitation.currentState().workspaceName == nil &&
                                              models.invitation.currentState().deliveryLink == nil, "recreated-root-gets-only-safe-ledger-notification")
                                        models.identity.onEvent(event: IdentityEventRetry.shared)
                                        poll({ models.identity.currentState().verifiedRole == "client" }) {
                                            models.identity.onEvent(event: IdentityEventSignOut.shared)
                                            poll({ models.identity.currentState().status == .signedout }) { enterB() }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private static func enterB() {
        let next = models.identity.activeClient()
        _ = next.signIn(email: "pr3-ios-trainer@example.invalid", password: password, epoch: 0) { failure in
            check(failure == nil, "B-sign-in-after-ordered-cleanup")
            poll({ models.identity.currentState().status == .trainerhome }) {
                models.synchronize()
                let stateB = models.invitation.currentState()
                completionHeld!()
                check(stateB == models.invitation.currentState() && originalModels!.invitation.currentState() == oldState,
                      "retired-generation-callback-cannot-reach-B-or-cleared-root")
                check(stateB.workspaceName == nil && stateB.deliveryLink == nil && stateB.summaries.isEmpty, "no-A-presentation-in-B")
                models.identity.onEvent(event: IdentityEventSignOut.shared)
                poll({ models.identity.currentState().status == .signedout }) {
                    models.clear(); check(true, "LATE_COMPLETION_AND_A_B_FENCE"); exit(0)
                }
            }
        }
    }
}
#endif
