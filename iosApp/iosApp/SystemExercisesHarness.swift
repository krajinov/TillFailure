import Foundation
import Darwin
import Shared

#if DEBUG
enum SystemExercisesHarness {
    @MainActor private static var bridge: FirebaseNativeBridge!
    @MainActor private static var client: IosProductIdentityClient!
    @MainActor private static var root: NativeClientsModels!
    private static func check(_ ok: Bool, _ label: String) {
        print("M6_IOS \(label)=\(ok ? "PASS" : "FAIL")"); fflush(stdout)
        if !ok { exit(1) }
    }
    static func prepareUIIfRequested(bridge: FirebaseNativeBridge) {
        let env = ProcessInfo.processInfo.environment
        guard env["TF_M6_UI"] == "1", let prefix = env["TF_M6_PREFIX"], let password = env["TF_M6_PASSWORD"],
              prefix.range(of: "^m6_[a-z0-9_]{1,40}$", options: .regularExpression) != nil,
              bridge.debugCurrentUid == nil else { return }
        _ = bridge.signIn(email: "\(prefix)-ios@example.invalid", password: password, accountEpoch: 0) { result in
            check(result.failure == nil, "ui-fixture-auth")
        }
    }
    static func runIfRequested() {
        let env = ProcessInfo.processInfo.environment
        guard let phase = env["TF_M6_NATIVE"], let password = env["TF_M6_PASSWORD"], let prefix = env["TF_M6_PREFIX"],
              prefix.range(of: "^m6_[a-z0-9_]{1,40}$", options: .regularExpression) != nil else { return }
        Task { @MainActor in await run(phase, password, prefix) }
    }
    @MainActor private static func until(_ ready: () -> Bool) async {
        let end = Date().addingTimeInterval(40)
        while !ready() && Date() < end { try? await Task.sleep(nanoseconds: 50_000_000) }
        if !ready() { check(false, "timeout") }
    }
    @MainActor private static func read(_ uid: String, _ after: String? = nil, _ id: String? = nil) async -> ExerciseResult {
        await withCheckedContinuation { continuation in _ = client.exercises.read(uid: uid, afterId: after, exerciseId: id) { continuation.resume(returning: $0) } }
    }
    @MainActor private static func signIn(_ email: String, _ password: String) async -> StableFirebaseFailure? {
        await withCheckedContinuation { continuation in _ = client.signIn(email: email, password: password, epoch: 0) { continuation.resume(returning: $0) } }
    }
    @MainActor private static func run(_ phase: String, _ password: String, _ prefix: String) async {
        bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: UserDefaults(suiteName: prefix)!)
        let path = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent(prefix)
        client = IosProductIdentityClient(bridge: bridge, recoveryBridge: RecoveryPersistenceBridge(rootURL: path))
        let uid = "\(prefix)_ios", other = "\(prefix)_ios_b"
        if phase == "denied" {
            let original = client.invitations.pending(uid: other)
            check(original != nil, "original-restored")
            check(await read(other).error == .denied, "current-authority-denied")
            check(client.invitations.pending(uid: other) == original, "browsing-preserves-original")
            exit(0)
        }
        check(await signIn("\(prefix)-ios@example.invalid", password) == nil, "sign-in")
        root = NativeClientsModels(client: client)
        await until { root.identity.currentState().status == .trainerhome }
        let first = await read(uid)
        check(first.error == nil && first.items.count == 20 && first.nextId == "ex_20", "bounded-server-page")
        check(await read(uid, nil, "ex_01").detail?.id == "ex_01", "fresh-point-read")
        let last = await read(uid, first.nextId)
        check(last.items.count == 3 && last.nextId == nil, "terminal-page")
        var callbacks = 0
        let token = client.exercises.read(uid: uid, afterId: nil, exerciseId: nil) { _ in callbacks += 1 }; token.cancel()
        try? await Task.sleep(nanoseconds: 500_000_000)
        check(callbacks == 0, "cancelled-callback-fenced")
        check(client.freeze(uid: uid), "freeze")
        check(await read(uid).error == .unavailable, "frozen-read-rejected"); client.unfreeze(uid: uid)
        let disabled: NativeFirebaseUnitResult = await withCheckedContinuation { continuation in bridge.disableNetwork { continuation.resume(returning: $0) } }
        check(disabled.failure == nil, "sdk-network-disabled")
        check(await read(uid).error != nil, "no-cache-fallback")
        let enabled: NativeFirebaseUnitResult = await withCheckedContinuation { continuation in bridge.enableNetwork { continuation.resume(returning: $0) } }
        let reconnected = await read(uid)
        check(enabled.failure == nil && reconnected.items.count == 20, "fresh-reconnect")
        root.clear(); root = NativeClientsModels(client: client)
        await until { root.identity.currentState().status == .trainerhome }
        check(await read(uid).items.count == 20, "root-recreated-fresh-read")
        root.identity.onEvent(event: IdentityEventSignOut.shared)
        await until { root.identity.currentState().status == .signedout }
        client = root.identity.activeClient() as? IosProductIdentityClient; bridge = bridge.debugReplacementBridge; root.clear()
        check(await signIn("\(prefix)-ios_b@example.invalid", password) == nil, "clean-b-admission")
        root = NativeClientsModels(client: client); await until { root.identity.currentState().status == .trainerhome }
        let bPage = await read(other)
        check(client.invitations.pending(uid: other) == nil && bPage.items.count == 20, "b-isolation")
        bridge.debugBeforeInvitationTransport = { _ in /* Preserve original command before transport. */ }
        client.invitations.execute(uid: other, request: InvitationRequest(action: "create", workspaceId: "\(other)_ws", invitationId: nil,
            email: "synthetic-pending@example.invalid", token: nil, expectedVersion: nil,
            idempotencyKey: CommandResolutionKt.resolutionCommandKey(nonce: UUID().uuidString), onboarding: nil)) { _ in }
        await until { client.invitations.pending(uid: other) != nil }
        let original = client.invitations.pending(uid: other)
        check(await read(other).items.count == 20 && client.invitations.pending(uid: other) == original, "read-preserves-pending")
        root.identity.onEvent(event: IdentityEventSignOut.shared)
        await until { [.lockedrecovery, .departureblocked].contains(root.identity.currentState().status) }
        check(client.invitations.pending(uid: other) == original, "pending-blocks-departure")
        check(await signIn("\(prefix)-ios@example.invalid", password) != nil, "pending-blocks-a")
        root.clear(); exit(0)
    }
}
#endif
