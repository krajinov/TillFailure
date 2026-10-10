import Foundation
import Darwin
import Shared

#if DEBUG
/// Opt-in signed-simulator probe. The operator command runs in a separate host process.
enum TrainerProvisioningHarness {
    private static let resultURL = FileManager.default.temporaryDirectory.appendingPathComponent("pr2-trainer-results.txt")

    private static func record(_ line: String) {
        print(line)
        let data = Data((line + "\n").utf8)
        if FileManager.default.fileExists(atPath: resultURL.path), let file = try? FileHandle(forWritingTo: resultURL) {
            file.seekToEndOfFile()
            file.write(data)
            try? file.close()
        } else { try? data.write(to: resultURL, options: .atomic) }
    }

    private static func poll(timeout: TimeInterval, _ condition: @escaping () -> Bool, then completion: @escaping (Bool) -> Void) {
        let deadline = Date().addingTimeInterval(timeout)
        func tick() {
            if condition() { completion(true); return }
            if Date() >= deadline { completion(false); return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05, execute: tick)
        }
        tick()
    }

    static func runIfRequested() {
        let environment = ProcessInfo.processInfo.environment
        guard let stage = environment["TILLFAILURE_PR2_STAGE"], ["pending", "verified"].contains(stage) else { return }
        guard let password = environment["TF_PR2_TEST_PASSWORD"], password.count >= 8,
              let defaults = UserDefaults(suiteName: "tillfailure.pr2.trainer.harness") else {
            record("PR2_IOS configuration=FAIL"); exit(1)
        }
        if stage == "pending" {
            defaults.removePersistentDomain(forName: "tillfailure.pr2.trainer.harness")
            try? FileManager.default.removeItem(at: resultURL)
        }
        let recovery = RecoveryPersistenceBridge(rootURL: FileManager.default.temporaryDirectory.appendingPathComponent("TillFailurePR2TrainerHarness"))
        let bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let client = IosProductIdentityClient(bridge: bridge, recoveryBridge: recovery)
        func verify() {
            let restored = bridge.debugCurrentUid == "pr2_trainer"
            record("PR2_IOS_\(stage.uppercased()) auth=\(restored ? "PASS" : "FAIL")")
            guard restored else { exit(1) }
            let model = IdentityViewModel(initialClient: client)
            let target: GateStatus = stage == "pending" ? .workspacegate : .trainerhome
            poll(timeout: 20, { model.currentState().status == target }) { reached in
                let state = model.currentState()
                let valid = reached && (stage == "pending"
                    ? state.verifiedRole == nil && state.verifiedWorkspaceId == nil
                    : state.verifiedRole == "trainer" && state.verifiedWorkspaceId == "pr2_workspace")
                self.record("PR2_IOS_\(stage.uppercased()) gate=\(valid ? "PASS" : "FAIL")")
                _ = bridge.getDocument(path: "workspaces/pr2_workspace/trainerProfiles/pr2_trainer", accountEpoch: 0) { profile in
                    let profileValid = stage == "pending"
                        ? profile.failure?.code == "PERMISSION_DENIED"
                        : profile.failure == nil && profile.document?.isFromCache == false && profile.document?.fields["userId"] == "pr2_trainer"
                    self.record("PR2_IOS_\(stage.uppercased()) profile=\(profileValid ? "PASS" : "FAIL")")
                    exit(valid && profileValid ? 0 : 1)
                }
            }
        }
        if stage == "pending" {
            _ = bridge.signIn(email: "pr2-trainer@example.invalid", password: password, accountEpoch: 0) { result in
                guard result.failure == nil else { record("PR2_IOS_PENDING signIn=FAIL"); exit(1) }
                verify()
            }
        } else {
            // A new process must restore the same native Auth session before reading server state.
            verify()
        }
    }
}
#endif
