import Foundation
import Darwin
import Shared

#if DEBUG
enum ClientsHarness {
    private static var client: IosProductIdentityClient!
    private static var root: NativeClientsModels!
    private static let uid = "m5_ios_trainer", wid = "m5_ios_workspace"
    private static func check(_ ok: Bool, _ label: String) {
        print("M5_IOS \(label)=\(ok ? "PASS" : "FAIL")"); fflush(stdout)
        if !ok { exit(1) }
    }
    private static func until(_ ready: @escaping () -> Bool, _ next: @escaping () -> Void) {
        let deadline = Date().addingTimeInterval(40)
        func tick() {
            if ready() { next(); return }
            if Date() > deadline { check(false, "timeout"); return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05, execute: tick)
        }
        tick()
    }
    private static func scope() -> ClientScope { ClientScope(uid: uid, workspaceId: wid, trainerId: uid, authorityEpoch: root.identity.currentState().authorityEpoch) }
    static func runIfRequested() {
        guard ProcessInfo.processInfo.environment["TF_M5_NATIVE"] == "1", let password = ProcessInfo.processInfo.environment["TF_M5_PASSWORD"],
              let defaults = UserDefaults(suiteName: "tillfailure.m5.native") else { return }
        let bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let path = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TillFailureM5Native")
        client = IosProductIdentityClient(bridge: bridge, recoveryBridge: RecoveryPersistenceBridge(rootURL: path))
        if client.hasPinnedIdentity() || bridge.debugCurrentUid == uid || ProcessInfo.processInfo.environment["TF_M5_RESTORE"] == "1" {
            root = NativeClientsModels(client: client)
            until({ root.identity.currentState().status == .trainerhome }) {
                check(root.identity.currentState().accountUid == uid, "cold-auth-restore")
                check(root.roster.currentState().items.isEmpty, "cold-no-cached-roster")
                loadThenExit(signOut: ProcessInfo.processInfo.environment["TF_M5_RESTORE"] == "1")
            }
        } else {
            _ = client.signIn(email: "m5-ios-trainer@example.invalid", password: password, epoch: 0) { failure in
                if let failure { print("M5_IOS sign-in-category=\(failure.code)"); fflush(stdout) }
                check(failure == nil, "sign-in")
                root = NativeClientsModels(client: client)
                until({ root.identity.currentState().status == .trainerhome }) { loadThenExit(signOut: false) }
            }
        }
    }
    private static func loadThenExit(signOut: Bool) {
        root.roster.bind(scope: scope(), clientId: nil)
        until({ !root.roster.currentState().loading }) {
            let page = root.roster.currentState()
            check(page.error == nil && page.items.count == 2 && page.nextCursor != nil, "real-first-page")
            let first = page.items[0]
            root.details.bind(scope: scope(), clientId: first.clientId)
            until({ !root.details.currentState().loading }) {
                check(root.details.currentState().detail?.displayName == "Apple Fixture one", "real-details")
                root.roster.onEvent(event: ClientsEventLoadMore.shared)
                until({ !root.roster.currentState().loading }) {
                    check(root.roster.currentState().items.count == 3 && root.roster.currentState().nextCursor == nil && root.roster.currentState().items.last?.displayName == nil, "real-terminal-unnamed")
                    root.clear(); root = NativeClientsModels(client: client)
                    until({ root.identity.currentState().status == .trainerhome }) {
                        check(root.roster.currentState().items.isEmpty && root.details.currentState().detail == nil, "root-recreation-clears")
                        root.identity.onEvent(event: IdentityEventSuspend.shared)
                        check(root.identity.currentState().status == .loading, "background-gate-closes")
                        root.identity.onEvent(event: IdentityEventForeground.shared)
                        until({ root.identity.currentState().status == .trainerhome }) {
                            check(true, "foreground-reverified")
                            _ = client.clients.read(scope: scope(), request: ClientRequest(action: "get", workspaceId: wid, pageSize: nil, cursor: nil, clientId: "m5_android_one")) { denied in
                                check(denied.error == "access-lost", "foreign-details-denied")
                                if signOut {
                                    root.identity.onEvent(event: IdentityEventSignOut.shared)
                                    until({ root.identity.currentState().status == .signedout }) { check(true, "clean-sign-out"); root.clear(); exit(0) }
                                } else { print("M5_IOS RESTART_READY"); fflush(stdout); root.clear(); exit(0) }
                            }
                        }
                    }
                }
            }
        }
    }
}
#endif
