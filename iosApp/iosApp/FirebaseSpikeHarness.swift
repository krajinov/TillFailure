import Foundation
import Shared
import UIKit

#if DEBUG
enum FirebaseSpikeHarness {
    private static var lifecycleProbe: LifecycleProbe?

    static func runIfRequested(bridge: FirebaseNativeBridge) {
        guard ProcessInfo.processInfo.environment["TILLFAILURE_FIREBASE_SPIKE"] == "1" else { return }
        let epoch: Int64 = 1
        let session = bridge.observeSession(accountEpoch: epoch) { state in
            if state.uid != nil {
                print("M3_FIREBASE_SPIKE authObserver=PASS")
            }
        }
        bridge.signInAnonymously { result in
            switch result {
            case .failure(let error):
                print("M3_FIREBASE_SPIKE auth=FAIL code=\((error as NSError).code)")
            case .success(let uid):
                print("M3_FIREBASE_SPIKE auth=PASS")
                let acceptedPath = "spikeEcho/\(uid)/documents/native-ios"
                let listener = bridge.listenDocument(path: acceptedPath, accountEpoch: epoch) { result in
                    if let document = result.document {
                        print("M3_FIREBASE_SPIKE listener=PASS cache=\(document.isFromCache) pending=\(document.hasPendingWrites)")
                    }
                }
                let operations = DispatchGroup()
                operations.enter()
                _ = bridge.writeDocument(path: acceptedPath, fields: ["ownerUid": uid, "value": "accepted", "counter": "0"], accountEpoch: epoch) { writeResult in
                    print("M3_FIREBASE_SPIKE acceptedWrite=\(writeResult.failure == nil ? "PASS" : "FAIL")")
                    operations.enter()
                    _ = bridge.getDocument(path: acceptedPath, accountEpoch: epoch) { getResult in
                        print("M3_FIREBASE_SPIKE serverGet=\(getResult.document?.exists == true ? "PASS" : "FAIL")")
                        operations.leave()
                    }
                    operations.enter()
                    _ = bridge.increment(path: acceptedPath, field: "counter", by: 1, accountEpoch: epoch) { incrementResult in
                        print("M3_FIREBASE_SPIKE transaction=\(incrementResult.failure == nil ? "PASS" : "FAIL")")
                        operations.leave()
                    }
                    operations.enter()
                    _ = bridge.waitForPendingWrites(accountEpoch: epoch) { pendingResult in
                        print("M3_FIREBASE_SPIKE pendingWrites=\(pendingResult.failure == nil ? "PASS" : "FAIL")")
                        operations.leave()
                    }
                    operations.leave()
                }
                operations.enter()
                _ = bridge.writeDocument(path: "spikeEcho/other/documents/native-ios", fields: ["ownerUid": uid, "value": "forged", "counter": "0"], accountEpoch: epoch) { rejectedResult in
                    print("M3_FIREBASE_SPIKE rejectedWrite=\(rejectedResult.failure?.code == "PERMISSION_DENIED" ? "PASS" : "FAIL")")
                    operations.leave()
                }
                let cancelledRead = bridge.getDocument(path: acceptedPath, accountEpoch: epoch) { _ in
                    print("M3_FIREBASE_SPIKE cancellation=FAIL")
                }
                bridge.cancel(token: cancelledRead)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    print("M3_FIREBASE_SPIKE cancellation=PASS")
                    operations.notify(queue: .main) {
                        bridge.cancel(token: listener)
                        _ = bridge.getDocument(path: acceptedPath, accountEpoch: epoch) { _ in
                            print("M3_FIREBASE_SPIKE epochFencing=FAIL")
                        }
                        _ = bridge.getDocument(path: acceptedPath, accountEpoch: epoch + 1) { result in
                            print("M3_FIREBASE_SPIKE epochFencing=\(result.document?.exists == true ? "PASS" : "FAIL")")
                            bridge.cancel(token: session)
                            lifecycleProbe = LifecycleProbe(bridge: bridge, path: acceptedPath, epoch: epoch + 1) {
                                bridge.terminateAndClear { clearResult in
                                    print("M3_FIREBASE_SPIKE terminateAndClear=\(clearResult.failure == nil ? "PASS" : "FAIL")")
                                    lifecycleProbe = nil
                                }
                            }
                            print("M3_FIREBASE_SPIKE awaitingBackgroundForeground=READY")
                        }
                    }
                }
            }
        }
    }

    private final class LifecycleProbe {
        private let bridge: FirebaseNativeBridge
        private let path: String
        private let epoch: Int64
        private let completion: () -> Void
        private var sawBackground = false
        private var observers: [NSObjectProtocol] = []

        init(bridge: FirebaseNativeBridge, path: String, epoch: Int64, completion: @escaping () -> Void) {
            self.bridge = bridge
            self.path = path
            self.epoch = epoch
            self.completion = completion
            let center = NotificationCenter.default
            observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
                self?.sawBackground = true
            })
            observers.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
                guard let self, sawBackground else { return }
                removeObservers()
                _ = bridge.getDocument(path: path, accountEpoch: epoch) { result in
                    print("M3_FIREBASE_SPIKE backgroundForeground=\(result.document?.exists == true ? "PASS" : "FAIL")")
                    completion()
                }
            })
        }

        private func removeObservers() {
            let center = NotificationCenter.default
            observers.forEach(center.removeObserver)
            observers.removeAll()
        }

        deinit {
            removeObservers()
        }
    }
}
#endif
