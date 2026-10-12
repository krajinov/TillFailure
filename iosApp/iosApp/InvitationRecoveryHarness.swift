import Foundation
import FirebaseAuth
import FirebaseCore
import Darwin
import Shared

#if DEBUG
/// Real Swift SDK/protected-store/gate recovery. Host commits model lost replies; explicit wire
/// faults model service/authority errors. Actual Admin Auth fault injection is backend evidence.
enum InvitationRecoveryHarness {
    private static var bridge: FirebaseNativeBridge!
    private static var client: IosProductIdentityClient!
    private static var gate: IdentityViewModel!
    private static var ledger: InvitationLedger!
    private static var store: IosAtomicFilePersistence!
    private static var protectedBefore: AccountPersistenceEnvelope!
    private static var password = ""
    private static let actions = ["create", "resend", "revoke", "accept"]
    private static func check(_ value: Bool, _ label: String) {
        print("PR3_P1_IOS \(label)=\(value ? "PASS" : "FAIL")"); fflush(stdout)
        if !value { exit(1) }
    }
    private static func poll(_ status: GateStatus, then: @escaping () -> Void) {
        let deadline = Date().addingTimeInterval(35)
        func tick() {
            if gate.currentState().status == status { then(); return }
            if Date() >= deadline { check(false, "gate-timeout"); return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.03, execute: tick)
        }
        tick()
    }
    private static func fixture(_ action: String, _ operation: String, mode: String? = nil,
                                then: @escaping ([String: Any]) -> Void) {
        let active = bridge.activeBridge() as! FirebaseNativeBridge
        Auth.auth(app: FirebaseApp.app(name: active.debugAppName)!).currentUser!.getIDTokenForcingRefresh(true) { token, error in
            guard error == nil, let token else { check(false, "fixture-auth"); return }
            var request = URLRequest(url: URL(string: "http://127.0.0.1:9695/review/ios/\(action)/\(operation)")!)
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            request.timeoutInterval = 15
            if operation != "evidence" {
                request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.httpBody = try! JSONSerialization.data(withJSONObject: mode.map { ["mode": $0] } ?? [:])
            }
            URLSession.shared.dataTask(with: request) { data, response, error in
                DispatchQueue.main.async {
                    guard error == nil, (response as? HTTPURLResponse)?.statusCode == 200, let data, data.count <= 8192,
                          let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                        check(false, "fixture-response"); return
                    }
                    then(body)
                }
            }.resume()
        }
    }
    static func runIfRequested() {
        guard ProcessInfo.processInfo.environment["TF_PR3_P1_RECOVERY"] == "1",
              let supplied = ProcessInfo.processInfo.environment["TF_PR3_TEST_PASSWORD"],
              let defaults = UserDefaults(suiteName: "tillfailure.pr3.p1.review") else { return }
        password = supplied
        bridge = FirebaseNativeBridge(productMemoryCache: true, identityDefaults: defaults)
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TillFailurePR3P1Review")
        let recovery = RecoveryPersistenceBridge(rootURL: root)
        client = IosProductIdentityClient(bridge: bridge, recoveryBridge: recovery)
        store = IosAtomicFilePersistence(bridge: recovery)
        ledger = InvitationLedger(store: store)
        _ = client.signIn(email: "p1-ios-trainer@example.invalid", password: password, epoch: 0) { failure in
            check(failure == nil, "trainer-sign-in")
            gate = IdentityViewModel(initialClient: client)
            poll(.trainerhome) { trial(0) }
        }
    }
    private static func trial(_ index: Int) {
        if index == actions.count {
            gate.onEvent(event: IdentityEventSignOut.shared)
            poll(.signedout) { check(true, "ordered-clean-departure"); exit(0) }; return
        }
        let action = actions[index]
        if action == "accept" {
            gate.onEvent(event: IdentityEventSignOut.shared)
            poll(.signedout) {
                client = gate.activeClient() as? IosProductIdentityClient
                _ = client.signIn(email: "p1-ios-client@example.invalid", password: password, epoch: 0) { failure in
                    check(failure == nil, "client-sign-in")
                    poll(.noaccount) { setup(index) }
                }
            }
        } else { setup(index) }
    }
    private static func setup(_ index: Int) {
        let action = actions[index]
        fixture(action, "setup") { body in
            let data = body["request"] as! [String: Any]
            let request = InvitationRequest(action: action, workspaceId: data["workspaceId"] as? String,
                invitationId: data["invitationId"] as? String, email: data["email"] as? String, token: data["token"] as? String,
                expectedVersion: (data["expectedVersion"] as? NSNumber).map { KotlinInt(int: $0.int32Value) },
                idempotencyKey: data["idempotencyKey"] as? String)
            let uid = "p1_ios_\(action == "accept" ? "client" : "trainer")"
            check(ledger.prepare(uid: uid, request: request), "\(action)-protected-before-transport")
            let original = ledger.pending(uid: uid)!
            protectedBefore = store.read(uid: uid)
            failures(index, request, uid, original, 0)
        }
    }
    private static func failures(_ index: Int, _ request: InvitationRequest, _ uid: String,
                                 _ original: PendingInvitationCommand, _ faultIndex: Int) {
        let action = actions[index]
        let faults = ["quota", "internal", "unauthorized", "conflict"]
        if faultIndex == faults.count { recover(index, request, uid); return }
        let fault = faults[faultIndex]
        func issue() {
            _ = client.invitations.execute(uid: uid, request: request) { outcome in
                let expected = ["quota": ["rate-limited"], "internal": ["unavailable", "invitation-unavailable"],
                                "unauthorized": ["unauthorized"], "conflict": ["idempotency-key-reused"]]
                check(expected[fault]!.contains(outcome.error ?? "") && store.read(uid: uid) == protectedBefore &&
                      outcome.ambiguous && outcome.reply == nil && ledger.pending(uid: uid) == original &&
                      !client.hasProvenEmptyCriticalWork(uid: uid), "\(action)-\(fault)-pending-preserved")
                gate.onEvent(event: IdentityEventSignOut.shared)
                poll(.departureblocked) {
                    _ = client.signIn(email: "p1-ios-client@example.invalid", password: password, epoch: 0) { failure in
                        check(failure?.code == .failedPrecondition && (bridge.activeBridge() as! FirebaseNativeBridge).debugCurrentUid == uid,
                              "\(action)-\(fault)-signout-and-B-blocked")
                        failures(index, request, uid, original, faultIndex + 1)
                    }
                }
            }
        }
        if fault == "quota" { issue() } else { fixture(action, "mode", mode: fault) { _ in issue() } }
    }
    private static func recover(_ index: Int, _ request: InvitationRequest, _ uid: String) {
        let action = actions[index]
        fixture(action, "mode", mode: "forward") { _ in
            _ = client.invitations.execute(uid: uid, request: request) { outcome in
                check(outcome.error == nil && !outcome.ambiguous && outcome.reply?.replayed?.boolValue == true &&
                      ledger.pending(uid: uid) == nil && client.hasProvenEmptyCriticalWork(uid: uid), "\(action)-receipt-resolved")
                fixture(action, "evidence") { evidence in
                    check(evidence["unchanged"] as? Bool == true && (evidence["admissionQuota"] as? NSNumber)?.intValue == 30,
                          "\(action)-unchanged-state-and-quota")
                    gate.onEvent(event: IdentityEventRetry.shared)
                    poll(action == "accept" ? .membershipverifiedfeaturepending : .trainerhome) { trial(index + 1) }
                }
            }
        }
    }
}
#endif
