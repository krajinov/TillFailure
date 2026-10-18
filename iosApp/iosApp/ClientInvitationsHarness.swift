import Foundation
import Darwin
import Shared

#if DEBUG
enum ClientInvitationsHarness {
    @MainActor private static var client: IosProductIdentityClient!
    @MainActor private static var root: NativeClientsModels!
    private static func check(_ ok: Bool, _ label: String) {
        print("M5P2_IOS \(label)=\(ok ? "PASS" : "FAIL")"); fflush(stdout)
        if !ok { exit(1) }
    }
    static func runIfRequested() {
        guard ProcessInfo.processInfo.environment["TF_M5_PR2_NATIVE"] == "1",
              let password = ProcessInfo.processInfo.environment["TF_M5_PR2_PASSWORD"] else { return }
        Task { @MainActor in await run(password) }
    }
    @MainActor private static func until(_ ready: () -> Bool) async {
        let deadline = Date().addingTimeInterval(40)
        while !ready() && Date() < deadline { try? await Task.sleep(nanoseconds: 50_000_000) }
        if !ready() { check(false, "timeout") }
    }
    @MainActor private static func login(_ role: String, _ status: GateStatus, _ password: String) async {
        let failure: StableFirebaseFailure? = await withCheckedContinuation { continuation in
            _ = client.signIn(email: "m5p2-ios-\(role)@example.invalid", password: password, epoch: 0) { continuation.resume(returning: $0) }
        }
        check(failure == nil, "sign-in")
        root = NativeClientsModels(client: client)
        await until { root.identity.currentState().status == status }
        root.invitations.updateIdentity(next: root.identity.currentState())
    }
    @MainActor private static func leave() async {
        root.identity.onEvent(event: IdentityEventSignOut.shared)
        await until { root.identity.currentState().status == .signedout }
        client = root.identity.activeClient() as? IosProductIdentityClient
        check(client != nil, "clean-departure")
        root.clear()
    }
    @MainActor private static func event(_ event: InvitationEvent) async {
        root.invitations.onEvent(event: event)
        await until { !root.invitations.currentState().busy }
    }
    @MainActor private static func roster(_ count: Int) async {
        root.roster.clear()
        root.roster.bind(scope: ClientScope(uid: "m5p2_ios_trainer", workspaceId: "m5p2_ios_workspace", trainerId: "m5p2_ios_trainer", authorityEpoch: root.identity.currentState().authorityEpoch), clientId: nil)
        await until { !root.roster.currentState().loading }
        check(root.roster.currentState().error == nil && root.roster.currentState().items.count == count, "fresh-authorized-roster")
    }
    @MainActor private static func create(_ role: String) async {
        root.invitations.enterTrainer(entry: .invite)
        await event(InvitationEventEmail(value: "m5p2-ios-\(role)@example.invalid"))
        await event(InvitationEventCreate.shared)
        check(root.invitations.currentState().pending == nil && root.invitations.currentState().deliveryLink != nil && root.invitations.currentState().listLoaded, "create-and-safe-list-refresh")
    }
    @MainActor private static func run(_ password: String) async {
        let defaults = UserDefaults(suiteName: "tillfailure.m5p2.native")!
        let bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let path = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TillFailureM5PR2Native")
        client = IosProductIdentityClient(bridge: bridge, recoveryBridge: RecoveryPersistenceBridge(rootURL: path))
        await login("trainer", .trainerhome, password)
        await roster(0)
        root.invitations.enterTrainer(entry: .manage)
        await until { !root.invitations.currentState().busy }
        check(root.invitations.currentState().summaries.isEmpty, "no-invitations")
        await create("revoke"); await roster(0)
        await event(InvitationEventListInvitations.shared)
        var summary = root.invitations.currentState().summaries[0]
        await event(InvitationEventResend(summary: summary))
        check(root.invitations.currentState().confirmation != nil, "resend-confirmation")
        await event(InvitationEventConfirm.shared)
        await event(InvitationEventListInvitations.shared)
        summary = root.invitations.currentState().summaries[0]
        check(summary.tokenVersion == 2, "resend-rotated")
        await event(InvitationEventRevoke(summary: summary)); await event(InvitationEventConfirm.shared)
        check(root.invitations.currentState().summaries[0].status == "revoked", "revoke-and-refresh")
        await roster(0)
        await create("accept"); await roster(0)
        await event(InvitationEventUseDelivery.shared)
        let denied: InvitationOutcome = await withCheckedContinuation { continuation in
            _ = client.invitations.execute(uid: "m5p2_ios_trainer", request: InvitationRequest(action: "list", workspaceId: "m5p2_android_workspace", invitationId: nil, email: nil, token: nil, expectedVersion: nil, idempotencyKey: nil, onboarding: nil)) { continuation.resume(returning: $0) }
        }
        check(denied.error == "unauthorized", "foreign-workspace-denied")
        await leave(); await login("accept", .noaccount, password)
        await event(InvitationEventPreview.shared)
        check(root.invitations.currentState().workspaceName != nil, "matching-preview")
        await event(InvitationEventAccept.shared)
        check(root.invitations.currentState().confirmation != nil, "accept-confirmation")
        await event(InvitationEventConfirm.shared)
        check(root.invitations.currentState().pending == nil, "protected-acceptance-settled")
        root.identity.onEvent(event: IdentityEventRetry.shared)
        await until { root.identity.currentState().status == .onboarding }
        await leave(); await login("trainer", .trainerhome, password)
        await roster(1)
        check(root.roster.currentState().items[0].clientId == "m5p2_ios_accept" && root.roster.currentState().items[0].displayName == nil, "accepted-client-only-after-fresh-read")
        await event(InvitationEventListInvitations.shared)
        check(Set(root.invitations.currentState().summaries.map { $0.status }) == Set(["accepted", "revoked"]), "safe-terminal-statuses")
        root.clear(); root = NativeClientsModels(client: client)
        await until { root.identity.currentState().status == .trainerhome }
        check(root.roster.currentState().items.isEmpty, "root-recreation-no-cached-roster")
        await roster(1); await leave(); exit(0)
    }
}
#endif
