import Foundation
import Shared

#if DEBUG
/// Opt-in native listener regression. Only seeded local emulator data is touched.
enum NativeRevocationHarness {
    private static var retainedBridge: FirebaseNativeBridge?
    private static let resultURL = FileManager.default.temporaryDirectory.appendingPathComponent("pr1-revocation-results.txt")

    static func runIfRequested(password: String?) {
        guard ProcessInfo.processInfo.environment["TILLFAILURE_PR1_REVOCATION"] == "1" else { return }
        try? FileManager.default.removeItem(at: resultURL)
        guard let password, let defaults = UserDefaults(suiteName: "tillfailure.pr1.revocation.harness") else {
            record("PR1_REVOCATION configuration=FAIL"); return
        }
        defaults.removePersistentDomain(forName: "tillfailure.pr1.revocation.harness")
        let bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        retainedBridge = bridge
        _ = bridge.signIn(email: "pr1-client@example.invalid", password: password, accountEpoch: 0) { auth in
            guard auth.failure == nil else { record("PR1_REVOCATION auth=FAIL"); return }
            let path = "workspaces/pr1_workspace/memberships/pr1_client"
            _ = bridge.getDocument(path: path, accountEpoch: 0) { initial in
                guard initial.document?.isFromCache == false,
                      initial.document?.fields["status"] == "active" else {
                    record("PR1_REVOCATION initialServer=FAIL"); return
                }
                record("PR1_REVOCATION initialServer=PASS")
                _ = bridge.listenDocument(path: path, accountEpoch: 0) { changed in
                    let denied = changed.failure?.code == "PERMISSION_DENIED"
                    let revoked = changed.document?.isFromCache == false &&
                        changed.document?.fields["status"] == "revoked"
                    if denied || revoked { record("PR1_REVOCATION listenerLocks=PASS") }
                }
            }
        }
    }

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
}
#endif
