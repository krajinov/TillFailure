import Foundation
import Darwin
import Shared

#if DEBUG
enum BasicProfileHarness {
    @MainActor private static var bridge: FirebaseNativeBridge!
    @MainActor private static var client: IosProductIdentityClient!
    @MainActor private static var root: NativeProfileModels!
    private static func check(_ ok: Bool, _ label: String) {
        print("M5P3_IOS \(label)=\(ok ? "PASS" : "FAIL")"); fflush(stdout)
        if !ok { exit(1) }
    }
    static func runIfRequested() {
        let env = ProcessInfo.processInfo.environment
        guard let phase = env["TF_M5_PR3_NATIVE"], ["stage", "resume", "conflict-stage", "conflict-resume", "lifecycle"].contains(phase),
              let password = env["TF_M5_PR3_PASSWORD"] else { return }
        Task { @MainActor in await run(phase, password) }
    }
    @MainActor private static func until(_ ready: () -> Bool) async {
        let deadline = Date().addingTimeInterval(45)
        while !ready() && Date() < deadline { try? await Task.sleep(nanoseconds: 50_000_000) }
        if !ready() { check(false, "timeout") }
    }
    @MainActor private static func run(_ phase: String, _ password: String) async {
        let uid = "m5p3_ios_client", wid = "m5p3_ios_workspace"
        bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: UserDefaults(suiteName: "tillfailure.m5p3.native")!)
        let path = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TillFailureM5PR3Native")
        client = IosProductIdentityClient(bridge: bridge, recoveryBridge: RecoveryPersistenceBridge(rootURL: path))
        if bridge.debugCurrentUid == nil {
            let error: StableFirebaseFailure? = await withCheckedContinuation { continuation in
                _ = client.signIn(email: "m5p3-ios-client@example.invalid", password: password, epoch: 0) { continuation.resume(returning: $0) }
            }
            check(error == nil, "sign-in")
        }
        check(bridge.debugCurrentUid == uid, "current-uid")
        root = NativeProfileModels(client: client)
        await until { [.clienthome, .lockedrecovery].contains(root.identity.currentState().status) }
        root.refresh()
        if phase == "lifecycle" {
            root.profile.attach(visit: 1)
            await until { !root.profile.currentState().busy && root.profile.currentState().baseline != nil }
            let initial = root.profile.currentState().baseline!
            var s = root.profile.currentState()
            root.profile.onEvent(owner: s.scope!, visit: s.visit, promptId: s.promptId, event: ProfileEventLeave(exit: .back))
            let dequeued = try! await root.nextNavigation()
            root.profile.suspendPresentation()
            check(!root.profile.consumeNavigation(result: dequeued) && root.profile.currentState().baseline == nil, "background-retires-dequeued-navigation")
            root.identity.onEvent(event: IdentityEventSuspend.shared); root.refresh()
            root.identity.onEvent(event: IdentityEventForeground.shared)
            await until { root.identity.currentState().status == .clienthome }
            root.refresh(); root.profile.attach(visit: 2)
            await until { !root.profile.currentState().busy && root.profile.currentState().baseline != nil }
            check(root.profile.currentState().baseline == initial && client.invitations.pending(uid: uid) == nil, "foreground-fresh-read-preserves-source")
            s = root.profile.currentState()
            root.profile.onEvent(owner: s.scope!, visit: s.visit, promptId: s.promptId, event: ProfileEventLeave(exit: .back))
            let current = try! await root.nextNavigation()
            check(!root.profile.consumeNavigation(result: dequeued), "old-effect-denied-after-reverification")
            check(root.profile.consumeNavigation(result: current) && !root.profile.consumeNavigation(result: current), "current-effect-consumed-once")
            root.clear(); check(!root.profile.consumeNavigation(result: current), "disposed-root-denies-effect")
            root = NativeProfileModels(client: client)
            await until { root.identity.currentState().status == .clienthome }
            root.refresh(); root.profile.attach(visit: 3)
            await until { !root.profile.currentState().busy && root.profile.currentState().baseline != nil }
            check(root.profile.currentState().baseline == initial, "recreated-root-fresh-source")
            root.identity.onEvent(event: IdentityEventSignOut.shared)
            await until { root.identity.currentState().status == .signedout }
            check(true, "lifecycle-clean-departure"); root.clear(); exit(0)
        }
        if phase.hasSuffix("stage") {
            root.profile.attach(visit: 1)
            await until { !root.profile.currentState().busy && root.profile.currentState().baseline != nil }
            var held = false
            bridge.debugBeforeInvitationCompletion = { _ in held = true }
            if phase == "stage" {
                let scope = root.profile.currentState().scope!
                root.profile.onEvent(owner: scope, visit: 1, promptId: 0, event: ProfileEventName(value: "Recovered Apple"))
                root.profile.onEvent(owner: scope, visit: 1, promptId: 0, event: ProfileEventLocale(value: root.profile.currentState().locale == "en" ? "bs-BA" : "en"))
                root.profile.onEvent(owner: scope, visit: 1, promptId: 0, event: ProfileEventSave.shared)
            } else {
                _ = client.invitations.execute(uid: uid, request: InvitationRequest(action: "update-profile", workspaceId: wid, invitationId: nil,
                    email: nil, token: nil, expectedVersion: nil, idempotencyKey: "native-conflict-original", onboarding: nil,
                    profileUpdate: ProfileUpdate(displayName: "Stale native", locale: "en", timeZoneId: "UTC", expectedRevision: 1))) { _ in }
            }
            await until { held }
            check(client.invitations.pending(uid: uid)?.action == "update-profile", "original-command-protected")
            root.identity.onEvent(event: IdentityEventSignOut.shared)
            await until { [.departureblocked, .lockedrecovery].contains(root.identity.currentState().status) }
            let error: StableFirebaseFailure? = await withCheckedContinuation { continuation in
                _ = client.signIn(email: "m5p3-ios-trainer@example.invalid", password: password, epoch: 0) { continuation.resume(returning: $0) }
            }
            check(error?.code == .failedPrecondition && bridge.debugCurrentUid == uid, "pending-blocks-account-departure")
            check(true, "RESPONSE_HELD")
            return
        }
        let original = client.invitations.pending(uid: uid)!
        check((original.profileUpdate?.expectedRevision ?? 0) >= 1, "restored-original-payload")
        root.identity.onEvent(event: IdentityEventSignOut.shared)
        await until { [.departureblocked, .lockedrecovery].contains(root.identity.currentState().status) }; root.refresh()
        bridge.debugBeforeInvitationTransport = { issue in
            check(client.invitations.pending(uid: uid) == original, "retry-original-command"); bridge.debugBeforeInvitationTransport = nil; issue()
        }
        let s = root.profile.currentState()
        root.profile.onEvent(owner: s.scope!, visit: s.visit, promptId: s.promptId, event: ProfileEventRetryOriginal.shared)
        await until { !root.profile.currentState().busy && client.invitations.pending(uid: uid) == nil }
        check(phase == "resume" ? root.profile.currentState().saved : root.profile.currentState().conflict, "validated-receipt-settled")
        root.identity.onEvent(event: IdentityEventRetry.shared)
        await until { root.identity.currentState().status == .clienthome }
        root.refresh(); root.profile.attach(visit: 2)
        await until { !root.profile.currentState().busy && root.profile.currentState().baseline != nil }
        check(root.profile.currentState().name == "Recovered Apple" && root.profile.currentState().baseline!.revision > original.profileUpdate!.expectedRevision, "fresh-profile-after-replay")
        root.clear(); root = NativeProfileModels(client: client)
        await until { root.identity.currentState().status == .clienthome }
        root.refresh(); root.profile.attach(visit: 3)
        await until { !root.profile.currentState().busy && root.profile.currentState().baseline != nil }
        check(root.profile.currentState().name == "Recovered Apple", "root-recreation-fresh-profile")
        root.identity.onEvent(event: IdentityEventSignOut.shared)
        await until { root.identity.currentState().status == .signedout }
        check(true, "clean-departure")
        client = root.identity.activeClient() as? IosProductIdentityClient
        root.clear()
        let signIn: StableFirebaseFailure? = await withCheckedContinuation { continuation in
            _ = client.signIn(email: "m5p3-ios-trainer@example.invalid", password: password, epoch: 0) { continuation.resume(returning: $0) }
        }
        check(signIn == nil, "trainer-sign-in"); root = NativeProfileModels(client: client)
        await until { root.identity.currentState().status == .trainerhome }
        let detail: ClientResult = await withCheckedContinuation { continuation in
            _ = client.clients.read(scope: ClientScope(uid: "m5p3_ios_trainer", workspaceId: wid, trainerId: "m5p3_ios_trainer", authorityEpoch: root.identity.currentState().authorityEpoch),
                request: ClientRequest(action: "get", workspaceId: wid, pageSize: nil, cursor: nil, clientId: uid)) { continuation.resume(returning: $0) }
        }
        check(detail.error == nil && detail.detail?.displayName == "Recovered Apple", "authorized-trainer-fresh-name")
        root.identity.onEvent(event: IdentityEventSignOut.shared)
        await until { root.identity.currentState().status == .signedout }
        root.clear(); exit(0)
    }
}
#endif
