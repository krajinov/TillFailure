import Foundation
import Shared

#if DEBUG
/// Native Swift bridge smoke proof against Admin-seeded local emulator identities.
enum ProductIdentityHarness {
    static func runIfRequested(bridge: NativeIdentityBridge) {
        let environment = ProcessInfo.processInfo.environment
        guard environment["TILLFAILURE_PR1_IDENTITY"] == "1" else { return }
        guard let password = environment["TF_PR1_TEST_PASSWORD"], password.count >= 8 else {
            print("PR1_IDENTITY configuration=FAIL")
            return
        }
        _ = bridge.signIn(email: "pr1-client@example.invalid", password: password, accountEpoch: 0) { result in
            guard result.failure == nil else { print("PR1_IDENTITY auth=FAIL code=\(result.failure?.code ?? "unknown")"); return }
            print("PR1_IDENTITY auth=PASS")
            _ = bridge.getDocument(path: "users/pr1_client", accountEpoch: 0) { account in
                let validAccount = account.document?.path == "users/pr1_client" &&
                    account.document?.isFromCache == false && account.document?.hasPendingWrites == false &&
                    account.document?.fields["schemaVersion"] == "1" && account.document?.fields["accountStatus"] == "active"
                print("PR1_IDENTITY accountServer=\(validAccount ? "PASS" : "FAIL")")
                guard validAccount else { return }
                _ = bridge.discoverMemberships(uid: "pr1_client", accountEpoch: 0) { discovery in
                    let validDiscovery = discovery.failure == nil && discovery.documents?.count == 1 &&
                        discovery.documents?.first?.path == "workspaces/pr1_workspace/memberships/pr1_client" &&
                        discovery.documents?.first?.isFromCache == false
                    print("PR1_IDENTITY boundedDiscovery=\(validDiscovery ? "PASS" : "FAIL")")
                    guard validDiscovery else { return }
                    _ = bridge.getDocument(path: "workspaces/pr1_workspace", accountEpoch: 0) { workspace in
                        _ = bridge.getDocument(path: "workspaces/pr1_workspace/memberships/pr1_client", accountEpoch: 0) { membership in
                            let validWorkspace = workspace.document?.path == "workspaces/pr1_workspace" &&
                                workspace.document?.isFromCache == false && workspace.document?.fields["status"] == "active"
                            let validMember = membership.document?.path == "workspaces/pr1_workspace/memberships/pr1_client" &&
                                membership.document?.isFromCache == false && membership.document?.hasPendingWrites == false &&
                                membership.document?.fields["workspaceId"] == "pr1_workspace" &&
                                membership.document?.fields["userId"] == "pr1_client" &&
                                membership.document?.fields["role"] == "client" && membership.document?.fields["status"] == "active"
                            print("PR1_IDENTITY workspaceMembershipServer=\(validWorkspace && validMember ? "PASS" : "FAIL")")
                            _ = bridge.signIn(email: "pr1-client@example.invalid", password: "deliberately-invalid", accountEpoch: 0) { invalid in
                                print("PR1_IDENTITY invalidCredentials=\(invalid.failure?.code == "INVALID_CREDENTIALS" ? "PASS" : "FAIL")")
                            }
                        }
                    }
                }
            }
        }
    }
}
#endif
