import Foundation
import Darwin
import Shared

#if DEBUG
/// Actual Swift Auth/Functions/Firestore SDKs, protected storage and the shared identity gate.
enum InvitationHarness {
    private static var gate: IdentityViewModel?
    private static var client: IosProductIdentityClient?
    private static var original: InvitationReply?
    private static var rotated: InvitationReply?
    private static var live: InvitationReply?
    private static var ledger: InvitationLedger?
    private static func check(_ value: Bool, _ label: String) {
        print("PR3_IOS \(label)=\(value ? "PASS" : "FAIL")")
        if !value { exit(1) }
    }
    private static func poll(_ status: GateStatus, then: @escaping () -> Void) {
        let deadline = Date().addingTimeInterval(30)
        func tick() {
            if gate?.currentState().status == status { then(); return }
            if Date() >= deadline { check(false, "gate"); return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05, execute: tick)
        }
        tick()
    }
    private static func command(_ uid: String, _ action: String, key: String? = nil, reply: InvitationReply? = nil,
                                then: @escaping (InvitationOutcome) -> Void) {
        let request = InvitationRequest(action: action,
            workspaceId: ["create", "list", "resend", "revoke"].contains(action) ? "pr3_ios_workspace" : nil,
            invitationId: reply?.invitationId, email: action == "create" ? "pr3-ios-client@example.invalid" : nil,
            token: ["accept", "preview"].contains(action) ? reply?.deliveryToken : nil,
            expectedVersion: reply?.tokenVersion, idempotencyKey: key, onboarding: nil)
        _ = client!.invitations.execute(uid: uid, request: request, callback: then)
    }
    static func runIfRequested() {
        guard ProcessInfo.processInfo.environment["TILLFAILURE_PR3_NATIVE"] == "1",
              let password = ProcessInfo.processInfo.environment["TF_PR3_TEST_PASSWORD"],
              let defaults = UserDefaults(suiteName: "tillfailure.pr3.invitation.harness") else { return }
        let bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let recovery = RecoveryPersistenceBridge(rootURL: FileManager.default.temporaryDirectory.appendingPathComponent("TillFailurePR3InvitationHarness"))
        client = IosProductIdentityClient(bridge: bridge, recoveryBridge: recovery)
        ledger = InvitationLedger(store: IosAtomicFilePersistence(bridge: recovery))
        _ = client!.signIn(email: "pr3-ios-trainer@example.invalid", password: password, epoch: 0) { failure in
            check(failure == nil, "trainer-sign-in")
            gate = IdentityViewModel(initialClient: client!)
            poll(.trainerhome) {
                check(gate!.currentState().verifiedRole == "trainer", "trainer-server-gate")
                command("pr3_ios_trainer", "create", key: "native-create") { created in
                    check(created.reply?.deliveryToken != nil, "create"); original = created.reply
                    command("pr3_ios_trainer", "resend", key: "native-resend", reply: original) { newer in
                        check(newer.reply?.tokenVersion?.intValue == 2, "rotate"); rotated = newer.reply
                        command("pr3_ios_trainer", "revoke", key: "native-revoke", reply: rotated) { revoked in
                            check(revoked.reply?.status == "revoked", "revoke")
                            command("pr3_ios_trainer", "create", key: "native-live") { active in
                                check(active.reply?.deliveryToken != nil, "create-live"); live = active.reply
                                command("pr3_ios_trainer", "list") { list in
                                    check(list.reply?.summaries?.count == 2, "safe-list")
                                    let link = InvitationLink(invitationId: live!.invitationId!, version: live!.tokenVersion!.int32Value, token: live!.deliveryToken!)
                                    check(client!.invitations.memory.open(value: link.entry(), uid: "pr3_ios_trainer"), "entry")
                                    gate!.onEvent(event: IdentityEventSignOut.shared)
                                    poll(.signedout) {
                                        client = gate!.activeClient() as? IosProductIdentityClient
                                        check(client?.invitations.memory.link != nil, "departure-link-continuation")
                                        signInClient(password)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private static func signInClient(_ password: String) {
        _ = client!.signIn(email: "pr3-ios-client@example.invalid", password: password, epoch: 0) { failure in
            check(failure == nil, "client-sign-in")
            poll(.noaccount) {
                command("pr3_ios_client", "preview", reply: original) { old in
                    check(old.error == "rotated", "old-link-denied")
                    command("pr3_ios_client", "preview", reply: rotated) { revoked in
                        check(revoked.error == "revoked", "revoked-link-denied")
                        command("pr3_ios_client", "preview", reply: live) { preview in
                            check(preview.reply?.workspaceName == "Apple Training" && gate!.currentState().verifiedRole == nil, "preview-no-role")
                            let pending = InvitationRequest(action: "accept", workspaceId: nil, invitationId: live!.invitationId,
                                email: nil, token: live!.deliveryToken, expectedVersion: live!.tokenVersion, idempotencyKey: "native-accept", onboarding: nil)
                            check(ledger!.prepare(uid: "pr3_ios_client", request: pending), "pending-command-recorded")
                            gate!.onEvent(event: IdentityEventSignOut.shared)
                            poll(.departureblocked) {
                              check(gate!.currentState().accountUid == "pr3_ios_client", "pending-departure-blocked")
                              command("pr3_ios_client", "accept", key: "native-accept", reply: live) { accepted in
                                check(accepted.reply?.status == "accepted" && gate!.currentState().verifiedRole == nil, "accept-no-role")
                                command("pr3_ios_client", "accept", key: "native-accept", reply: live) { replay in
                                    check(replay.reply?.replayed?.boolValue == true, "receipt-replay")
                                    check(client!.invitations.pending(uid: "pr3_ios_client") == nil, "protected-command-settled")
                                    gate!.onEvent(event: IdentityEventRetry.shared)
                                    poll(.onboarding) {
                                        check(gate!.currentState().verifiedRole == "client" && gate!.currentState().verifiedWorkspaceId == "pr3_ios_workspace", "fresh-client-server-gate")
                                        gate!.onEvent(event: IdentityEventSignOut.shared)
                                        poll(.signedout) { check(true, "client-clean-departure"); exit(0) }
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
}
#endif
