import Foundation
import Shared
import UIKit

#if DEBUG
enum FirebaseSpikeHarness {
    private static var lifecycleProbe: LifecycleProbe?
    private static var stalledProbe: StalledPendingWriteProbe?

    static func runIfRequested(bridge: FirebaseNativeBridge) {
        guard ProcessInfo.processInfo.environment["TILLFAILURE_FIREBASE_SPIKE"] == "1" else { return }
        _ = RecoveryPersistenceSpikeHarness.run()
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
                    _ = bridge.waitForPendingWrites(accountEpoch: epoch, timeoutMillis: 5_000) { pendingResult in
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
                        stalledProbe = StalledPendingWriteProbe(bridge: bridge, uid: uid, epoch: epoch) {
                            stalledProbe = nil
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

    private final class StalledPendingWriteProbe {
        private let bridge: FirebaseNativeBridge
        private let uid: String
        private let epoch: Int64
        private let completion: () -> Void
        private let lock = NSLock()
        private var listenerToken: String?
        private var metadataCallbackCount = 0
        private var sawPending = false
        private var pendingOriginPassed = false
        private var acknowledgedOriginPassed = false
        private var metadataAcknowledged: (() -> Void)?
        private var cancellationCallbackCount = 0
        private var timeoutCallbackCount = 0

        init(bridge: FirebaseNativeBridge, uid: String, epoch: Int64, completion: @escaping () -> Void) {
            self.bridge = bridge
            self.uid = uid
            self.epoch = epoch
            self.completion = completion
            start()
        }

        private func start() {
            let path = "spikeEcho/\(uid)/documents/native-ios-stalled"
            let ready = DispatchGroup()
            ready.enter()
            listenerToken = bridge.listenDocument(path: path, accountEpoch: epoch) { [weak self] result in
                guard let self, let document = result.document, document.exists else { return }
                lock.lock()
                metadataCallbackCount += 1
                if document.hasPendingWrites {
                    if !sawPending {
                        sawPending = true
                        pendingOriginPassed = document.isFromCache
                        lock.unlock()
                        print("M3_FIREBASE_SPIKE metadataPending=\(document.isFromCache ? "PASS" : "FAIL")")
                        ready.leave()
                        return
                    }
                } else if sawPending {
                    acknowledgedOriginPassed = !document.isFromCache
                    let acknowledged = metadataAcknowledged
                    metadataAcknowledged = nil
                    lock.unlock()
                    acknowledged?()
                    return
                }
                lock.unlock()
            }
            bridge.disableNetwork { [weak self] result in
                guard let self else { return }
                guard result.failure == nil else {
                    print("M3_FIREBASE_SPIKE stalledSetup=FAIL")
                    completion()
                    return
                }
                _ = bridge.writeDocument(
                    path: path,
                    fields: ["ownerUid": uid, "value": "stalled", "counter": "0"],
                    accountEpoch: epoch
                ) { _ in }

                let cancellation = bridge.waitForPendingWrites(accountEpoch: epoch, timeoutMillis: 5_000) { [weak self] waitResult in
                    guard let self else { return }
                    lock.lock()
                    cancellationCallbackCount += 1
                    let passed = waitResult.failure?.code == "CANCELLED" && cancellationCallbackCount == 1
                    lock.unlock()
                    print("M3_FIREBASE_SPIKE stalledCancellation=\(passed ? "PASS" : "FAIL")")
                }
                bridge.cancel(token: cancellation)

                ready.enter()
                _ = bridge.waitForPendingWrites(accountEpoch: epoch, timeoutMillis: 300) { [weak self] waitResult in
                    guard let self else { return }
                    lock.lock()
                    timeoutCallbackCount += 1
                    let passed = waitResult.failure?.code == "DEADLINE_EXCEEDED" && timeoutCallbackCount == 1
                    lock.unlock()
                    print("M3_FIREBASE_SPIKE stalledTimeout=\(passed ? "PASS" : "FAIL")")
                    ready.leave()
                }

                ready.notify(queue: .main) { [weak self] in
                    self?.settle(path: path)
                }
            }
        }

        private func settle(path: String) {
            let settled = DispatchGroup()
            settled.enter()
            settled.enter()
            lock.lock()
            if acknowledgedOriginPassed {
                lock.unlock()
                settled.leave()
            } else {
                metadataAcknowledged = { settled.leave() }
                lock.unlock()
            }
            bridge.enableNetwork { [weak self] result in
                guard let self else { return }
                guard result.failure == nil else {
                    print("M3_FIREBASE_SPIKE stalledSettlement=FAIL")
                    settled.leave()
                    settled.leave()
                    completion()
                    return
                }
                _ = bridge.waitForPendingWrites(accountEpoch: epoch, timeoutMillis: 5_000) { [weak self] waitResult in
                    guard let self else { return }
                    settled.leave()
                    settled.notify(queue: .main) { [weak self] in
                        guard let self else { return }
                        verifySettled(path: path, waitResult: waitResult)
                    }
                }
            }
        }

        private func verifySettled(path: String, waitResult: NativeFirebaseUnitResult) {
            lock.lock()
            let callbackCountsPassed = cancellationCallbackCount == 1 && timeoutCallbackCount == 1
            let metadataPassed = pendingOriginPassed && acknowledgedOriginPassed
            let callbacksBeforeDisposal = metadataCallbackCount
            lock.unlock()
            print("M3_FIREBASE_SPIKE stalledLateCallbackFence=\(waitResult.failure == nil && callbackCountsPassed ? "PASS" : "FAIL")")
            print("M3_FIREBASE_SPIKE metadataTransition=\(metadataPassed ? "PASS" : "FAIL")")
            if let listenerToken { bridge.cancel(token: listenerToken) }
            verifyDisposal(path: path, callbacksBeforeDisposal: callbacksBeforeDisposal)
        }

        private func verifyDisposal(path: String, callbacksBeforeDisposal: Int) {
            _ = bridge.writeDocument(
                path: path,
                fields: ["ownerUid": uid, "value": "after-disposal", "counter": "0"],
                accountEpoch: epoch
            ) { [weak self] writeResult in
                guard let self else { return }
                _ = bridge.waitForPendingWrites(accountEpoch: epoch, timeoutMillis: 5_000) { [weak self] drainResult in
                    guard let self else { return }
                    lock.lock()
                    let unchanged = metadataCallbackCount == callbacksBeforeDisposal
                    lock.unlock()
                    print("M3_FIREBASE_SPIKE listenerDisposal=\(writeResult.failure == nil && drainResult.failure == nil && unchanged ? "PASS" : "FAIL")")
                    verifyEpochFence()
                }
            }
        }

        private func verifyEpochFence() {
            let path = "spikeEcho/\(uid)/documents/native-ios-epoch"
            bridge.disableNetwork { [weak self] result in
                guard let self else { return }
                guard result.failure == nil else {
                    print("M3_FIREBASE_SPIKE epochFencing=FAIL")
                    completion()
                    return
                }
                let staleWrite = bridge.writeDocument(
                    path: path,
                    fields: ["ownerUid": uid, "value": "old-epoch", "counter": "0"],
                    accountEpoch: epoch
                ) { _ in
                    print("M3_FIREBASE_SPIKE epochFencing=FAIL")
                }
                var staleCallbacks = 0
                let staleWait = bridge.waitForPendingWrites(accountEpoch: epoch, timeoutMillis: 5_000) { _ in
                    self.lock.lock()
                    staleCallbacks += 1
                    self.lock.unlock()
                    print("M3_FIREBASE_SPIKE epochFencing=FAIL")
                }
                let nextEpochListener = bridge.listenDocument(path: path, accountEpoch: epoch + 1) { _ in }
                bridge.cancel(token: nextEpochListener)
                bridge.enableNetwork { [weak self] enabled in
                    guard let self else { return }
                    _ = bridge.waitForPendingWrites(accountEpoch: epoch + 1, timeoutMillis: 5_000) { drained in
                        self.bridge.cancel(token: staleWait)
                        self.bridge.cancel(token: staleWrite)
                        self.lock.lock()
                        let noStaleCallbacks = staleCallbacks == 0
                        self.lock.unlock()
                        print("M3_FIREBASE_SPIKE epochFencing=\(enabled.failure == nil && drained.failure == nil && noStaleCallbacks ? "PASS" : "FAIL")")
                        self.completion()
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
