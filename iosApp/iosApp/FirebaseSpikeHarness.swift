import FirebaseCore
import FirebaseFirestore
import Foundation
import Shared
import UIKit

#if DEBUG
enum FirebaseSpikeHarness {
    private static var lifecycleProbe: LifecycleProbe?
    private static var stalledProbe: StalledPendingWriteProbe?
    private static var registryProbe: RegistryLifecycleProbe?
    private static var counterProbe: CounterParityProbe?
    private static var terminationRecreationProbe: TerminationRecreationProbe?
    private static var overlappingClientsProbe: OverlappingClientsProbe?
    private static var issuanceProbe: IssuanceTeardownProbe?

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
                        registryProbe = RegistryLifecycleProbe(bridge: bridge, uid: uid, epoch: epoch) {
                            registryProbe = nil
                            counterProbe = CounterParityProbe(bridge: bridge, uid: uid, epoch: epoch) {
                                counterProbe = nil
                                stalledProbe = StalledPendingWriteProbe(bridge: bridge, uid: uid, epoch: epoch) {
                                    stalledProbe = nil
                                    bridge.cancel(token: session)
                                    lifecycleProbe = LifecycleProbe(bridge: bridge, path: acceptedPath, epoch: epoch + 1) {
                                        bridge.terminateAndClear { clearResult in
                                            print("M3_FIREBASE_SPIKE terminateAndClear=\(clearResult.failure == nil ? "PASS" : "FAIL")")
                                            print("M3_FIREBASE_SPIKE oneShotRegistryTerminated=\(bridge.debugCancellationCount == 0 ? "PASS" : "FAIL")")
                                            lifecycleProbe = nil
                                            terminationRecreationProbe = TerminationRecreationProbe(
                                                retiredBridge: bridge,
                                                retiredEpoch: epoch + 1,
                                                projectID: "demo-tillfailure-m3"
                                            ) {
                                                terminationRecreationProbe = nil
                                                overlappingClientsProbe = OverlappingClientsProbe(projectID: "demo-tillfailure-m3") {
                                                    overlappingClientsProbe = nil
                                                    issuanceProbe = IssuanceTeardownProbe {
                                                        issuanceProbe = nil
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    print("M3_FIREBASE_SPIKE awaitingBackgroundForeground=READY")
                                }
                            }
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

    private final class CounterParityProbe {
        private enum Seeding {
            case missing
            case value(Any)
            case keep
        }

        private enum Expected {
            case value(String)
            case rejected
        }

        private struct Step {
            let name: String
            let seeding: Seeding
            let delta: Int64
            let expected: Expected
        }

        private static let steps: [Step] = [
            Step(name: "counterMissingStartsAtZero", seeding: .missing, delta: 1, expected: .value("1")),
            Step(name: "counterNumericStringFiveBecomesSix", seeding: .value("5"), delta: 1, expected: .value("6")),
            Step(name: "counterIntegralValueIncrements", seeding: .keep, delta: 1, expected: .value("7")),
            Step(name: "counterZeroIncrements", seeding: .value("0"), delta: 1, expected: .value("1")),
            Step(name: "counterNegativeValueIncrements", seeding: .value("-3"), delta: 1, expected: .value("-2")),
            Step(name: "counterNegativeDeltaApplies", seeding: .value("5"), delta: -2, expected: .value("3")),
            Step(name: "counterSequentialIncrementOne", seeding: .value("5"), delta: 1, expected: .value("6")),
            Step(name: "counterSequentialIncrementTwo", seeding: .keep, delta: 1, expected: .value("7")),
            Step(name: "counterSequentialIncrementThree", seeding: .keep, delta: 1, expected: .value("8")),
            Step(name: "counterSequentialIncrementFour", seeding: .keep, delta: 1, expected: .value("9")),
            Step(name: "counterSequentialIncrementFive", seeding: .keep, delta: 1, expected: .value("10")),
            Step(name: "counterMalformedStringRejected", seeding: .value("abc"), delta: 1, expected: .rejected),
            Step(name: "counterDecimalStringRejected", seeding: .value("5.5"), delta: 1, expected: .rejected),
            Step(name: "counterWhitespaceStringRejected", seeding: .value(" 5"), delta: 1, expected: .rejected),
            Step(name: "counterPlusPrefixedStringRejected", seeding: .value("+5"), delta: 1, expected: .rejected),
            Step(name: "counterEmptyStringRejected", seeding: .value(""), delta: 1, expected: .rejected),
            Step(name: "counterBooleanRejected", seeding: .value(true), delta: 1, expected: .rejected),
            Step(name: "counterFloatingPointRejected", seeding: .value(5.0), delta: 1, expected: .rejected),
            Step(name: "counterCollectionRejected", seeding: .value(["x"]), delta: 1, expected: .rejected),
            Step(name: "counterOverflowRejected", seeding: .value("9223372036854775807"), delta: 1, expected: .rejected)
        ]

        private let bridge: FirebaseNativeBridge
        private let uid: String
        private let epoch: Int64
        private let path: String
        private let completion: () -> Void
        private let firestore: Firestore?
        private let deliveryLock = NSLock()
        private var deliveries = 0
        private var registryBaseline = 0

        init(bridge: FirebaseNativeBridge, uid: String, epoch: Int64, completion: @escaping () -> Void) {
            self.bridge = bridge
            self.uid = uid
            self.epoch = epoch
            self.path = "spikeEcho/\(uid)/documents/native-ios-counter"
            self.completion = completion
            // The harness reaches the same emulator-configured Firestore instance to seed typed
            // fixtures (booleans, doubles, arrays) that the string-typed bridge cannot write.
            let app = FirebaseApp.allApps?.values.first { $0.options.projectID?.hasPrefix("demo-") == true }
            self.firestore = app.map { Firestore.firestore(app: $0) }
            self.registryBaseline = bridge.debugCancellationCount
            run(steps: Self.steps, index: 0)
        }

        private func run(steps: [Step], index: Int) {
            guard index < steps.count else {
                record("counterDeliveryOnce", currentDeliveries() == steps.count)
                record("counterRegistryCleanup", bridge.debugCancellationCount == registryBaseline)
                completion()
                return
            }
            let step = steps[index]
            applySeeding(step.seeding) { [weak self] seeded in
                guard let self else { return }
                guard seeded else {
                    self.record(step.name, false)
                    self.completion()
                    return
                }
                self.storedDescription { [weak self] before in
                    guard let self else { return }
                    self.increment(by: step.delta) { [weak self] result, firstDelivery in
                        guard let self, firstDelivery else { return }
                        switch step.expected {
                        case .value(let expected):
                            self.record(step.name, result.failure == nil && result.document?.fields["counter"] == expected)
                            self.run(steps: steps, index: index + 1)
                        case .rejected:
                            self.storedDescription { [weak self] after in
                                guard let self else { return }
                                let rejected = result.failure?.code == "INVALID_ARGUMENT" && result.failure?.retryable == false
                                self.record(step.name, rejected && before == after)
                                self.run(steps: steps, index: index + 1)
                            }
                        }
                    }
                }
            }
        }

        private func record(_ name: String, _ condition: Bool) {
            print("M3_FIREBASE_SPIKE \(name)=\(condition ? "PASS" : "FAIL")")
        }

        private func currentDeliveries() -> Int {
            deliveryLock.lock()
            defer { deliveryLock.unlock() }
            return deliveries
        }

        private func applySeeding(_ seeding: Seeding, completion: @escaping (Bool) -> Void) {
            guard let firestore else { completion(false); return }
            switch seeding {
            case .keep:
                completion(true)
            case .missing:
                firestore.document(path).setData(["ownerUid": uid, "counter": NSNull()]) { error in completion(error == nil) }
            case .value(let value):
                firestore.document(path).setData(["ownerUid": uid, "counter": value]) { error in completion(error == nil) }
            }
        }

        private func storedDescription(completion: @escaping (String) -> Void) {
            guard let firestore else { completion("<unavailable>"); return }
            firestore.document(path).getDocument(source: .server) { snapshot, _ in
                completion(Self.describe(snapshot?.get("counter")))
            }
        }

        private func increment(by delta: Int64, completion: @escaping (NativeFirebaseDocumentResult, Bool) -> Void) {
            let callLock = NSLock()
            var callDeliveries = 0
            _ = bridge.increment(path: path, field: "counter", by: delta, accountEpoch: epoch) { [weak self] result in
                guard let self else { return }
                callLock.lock()
                callDeliveries += 1
                let isFirstDelivery = callDeliveries == 1
                callLock.unlock()
                self.deliveryLock.lock()
                self.deliveries += 1
                self.deliveryLock.unlock()
                if isFirstDelivery { completion(result, true) }
            }
        }

        private static func describe(_ value: Any?) -> String {
            guard let value else { return "<nil>" }
            if let number = value as? NSNumber {
                if CFGetTypeID(number) == CFBooleanGetTypeID() { return "bool:\(number.boolValue)" }
                if CFNumberIsFloatType(number) { return "double:\(number.doubleValue)" }
                return "int:\(number.int64Value)"
            }
            if let text = value as? String { return "string:\(text)" }
            return "other:\(String(describing: value))"
        }
    }

    private final class RegistryLifecycleProbe {
        private let bridge: FirebaseNativeBridge
        private let uid: String
        private let path: String
        private let epoch: Int64
        private let completion: () -> Void
        private let lock = NSLock()
        private var deliveryCounts: [String: Int] = [:]
        private var baseline = 0

        init(bridge: FirebaseNativeBridge, uid: String, epoch: Int64, completion: @escaping () -> Void) {
            self.bridge = bridge
            self.uid = uid
            self.path = "spikeEcho/\(uid)/documents/native-ios-registry"
            self.epoch = epoch
            self.completion = completion
            start()
        }

        private func record(_ name: String) {
            lock.lock()
            deliveryCounts[name, default: 0] += 1
            lock.unlock()
        }

        private func deliveries(_ name: String) -> Int {
            lock.lock()
            defer { lock.unlock() }
            return deliveryCounts[name] ?? 0
        }

        private func registrySize() -> Int {
            bridge.debugCancellationCount
        }

        private func start() {
            baseline = registrySize()
            // A listener entry must remain registered until it is explicitly disposed.
            let listenerToken = bridge.listenDocument(path: path, accountEpoch: epoch) { _ in }
            print("M3_FIREBASE_SPIKE oneShotRegistryListenerRetention=\(registrySize() == baseline + 1 ? "PASS" : "FAIL")")
            var firstGetToken = ""
            _ = bridge.writeDocument(path: path, fields: ["ownerUid": uid, "value": "registry", "counter": "0"], accountEpoch: epoch) { [weak self] writeResult in
                guard let self else { return }
                self.record("write")
                let writeClean = writeResult.failure == nil && self.registrySize() == self.baseline + 1
                firstGetToken = self.bridge.getDocument(path: self.path, accountEpoch: self.epoch) { [weak self] getResult in
                    guard let self else { return }
                    self.record("get1")
                    self.repeatReads(remaining: 2, cleanSoFar: writeClean && getResult.document?.exists == true, completedToken: firstGetToken, listenerToken: listenerToken)
                }
            }
        }

        private func repeatReads(remaining: Int, cleanSoFar: Bool, completedToken: String, listenerToken: String) {
            guard remaining > 0 else {
                finishReads(cleanSoFar: cleanSoFar, completedToken: completedToken, listenerToken: listenerToken)
                return
            }
            _ = bridge.getDocument(path: path, accountEpoch: epoch) { [weak self] getResult in
                guard let self else { return }
                self.record("read\(remaining)")
                self.repeatReads(
                    remaining: remaining - 1,
                    cleanSoFar: cleanSoFar && getResult.document?.exists == true && self.registrySize() == self.baseline + 1,
                    completedToken: completedToken,
                    listenerToken: listenerToken
                )
            }
        }

        private func finishReads(cleanSoFar: Bool, completedToken: String, listenerToken: String) {
            // Repeated successful reads/writes must not grow the registry.
            print("M3_FIREBASE_SPIKE oneShotRegistryNoGrowth=\(cleanSoFar && registrySize() == baseline + 1 ? "PASS" : "FAIL")")
            _ = bridge.writeDocument(path: "spikeEcho/other/documents/native-ios-registry", fields: ["ownerUid": uid, "value": "forged", "counter": "0"], accountEpoch: epoch) { [weak self] rejectedResult in
                guard let self else { return }
                self.record("failedWrite")
                // A failed one-shot must remove its token too.
                let failureClean = rejectedResult.failure?.code == "PERMISSION_DENIED" && self.registrySize() == self.baseline + 1
                print("M3_FIREBASE_SPIKE oneShotRegistryFailure=\(failureClean ? "PASS" : "FAIL")")
                self.cancelAfterCompletion(completedToken: completedToken, listenerToken: listenerToken)
            }
        }

        private func cancelAfterCompletion(completedToken: String, listenerToken: String) {
            // Cancelling an already-completed token is a safe no-op with no extra delivery.
            bridge.cancel(token: completedToken)
            let harmless = registrySize() == baseline + 1 && deliveries("get1") == 1
            print("M3_FIREBASE_SPIKE oneShotRegistryCancelAfterCompletion=\(harmless ? "PASS" : "FAIL")")
            cancelPendingRead(listenerToken: listenerToken)
        }

        private func cancelPendingRead(listenerToken: String) {
            bridge.disableNetwork { [weak self] disabled in
                guard let self else { return }
                guard disabled.failure == nil else {
                    print("M3_FIREBASE_SPIKE oneShotRegistryCancel=FAIL")
                    print("M3_FIREBASE_SPIKE oneShotRegistrySuppression=FAIL")
                    self.finish(listenerToken: listenerToken)
                    return
                }
                let pendingToken = self.bridge.getDocument(path: self.path, accountEpoch: self.epoch) { [weak self] _ in
                    self?.record("cancelledGet")
                }
                let registered = self.registrySize() == self.baseline + 2
                self.bridge.cancel(token: pendingToken)
                // Explicit cancellation removes the entry synchronously.
                let removed = self.registrySize() == self.baseline + 1
                print("M3_FIREBASE_SPIKE oneShotRegistryCancel=\(registered && removed ? "PASS" : "FAIL")")
                self.bridge.enableNetwork { [weak self] enabled in
                    guard let self else { return }
                    _ = self.bridge.getDocument(path: self.path, accountEpoch: self.epoch) { [weak self] roundTrip in
                        guard let self else { return }
                        self.record("postEnableGet")
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                            guard let self else { return }
                            // The cancelled read's late SDK completion must never reach the consumer.
                            let suppressed = enabled.failure == nil && roundTrip.document?.exists == true && self.deliveries("cancelledGet") == 0
                            print("M3_FIREBASE_SPIKE oneShotRegistrySuppression=\(suppressed ? "PASS" : "FAIL")")
                            self.finish(listenerToken: listenerToken)
                        }
                    }
                }
            }
        }

        private func finish(listenerToken: String) {
            _ = bridge.increment(path: path, field: "counter", by: 1, accountEpoch: epoch) { [weak self] incrementResult in
                guard let self else { return }
                self.record("increment")
                let successClean = incrementResult.failure == nil && self.registrySize() == self.baseline + 1
                print("M3_FIREBASE_SPIKE oneShotRegistrySuccess=\(successClean ? "PASS" : "FAIL")")
                self.lock.lock()
                let atMostOnce = self.deliveryCounts.values.allSatisfy { $0 == 1 }
                self.lock.unlock()
                print("M3_FIREBASE_SPIKE oneShotRegistryDeliveryOnce=\(atMostOnce ? "PASS" : "FAIL")")
                self.bridge.cancel(token: listenerToken)
                print("M3_FIREBASE_SPIKE oneShotRegistryListenerDisposal=\(self.registrySize() == self.baseline ? "PASS" : "FAIL")")
                self.completion()
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

    /// Same-process termination/recreation regression:
    ///
    ///  1. the retired bridge must answer later operations with a non-retryable FAILED_PRECONDITION
    ///     failure and must never deliver a document again;
    ///  2. two terminate/recreate cycles plus a final live client prove that constructing a bridge
    ///     for the same project in the same process obtains a fresh, usable SDK instance every time;
    ///  3. callbacks issued with the retired epoch must never reach a newer bridge.
    private final class TerminationRecreationProbe {
        private let retiredBridge: FirebaseNativeBridge
        private let retiredEpoch: Int64
        private let projectID: String
        private let completion: () -> Void
        private var stage = 0
        private var operationsCompleted = 0
        /// Mirrors how the app keeps its bridge alive for the lifetime of an account session, so a
        /// cycle's client is never released while its teardown is still completing.
        private var activeBridge: FirebaseNativeBridge?

        init(retiredBridge: FirebaseNativeBridge, retiredEpoch: Int64, projectID: String, completion: @escaping () -> Void) {
            self.retiredBridge = retiredBridge
            self.retiredEpoch = retiredEpoch
            self.projectID = projectID
            self.completion = completion
            assertRetiredClientFailsClosed()
        }

        private func assertRetiredClientFailsClosed() {
            _ = retiredBridge.getDocument(path: "spikeEcho/retired/documents/native-ios", accountEpoch: retiredEpoch) { [weak self] result in
                guard let self else { return }
                let unusable = result.failure?.code == "FAILED_PRECONDITION" && result.failure?.retryable == false && result.document == nil
                print("M3_FIREBASE_SPIKE retiredClientUnusable=\(unusable ? "PASS" : "FAIL")")
                _ = self.retiredBridge.writeDocument(
                    path: "spikeEcho/retired/documents/native-ios",
                    fields: ["ownerUid": "retired", "value": "stale", "counter": "0"],
                    accountEpoch: self.retiredEpoch
                ) { writeResult in
                    let denied = writeResult.failure?.code == "FAILED_PRECONDITION"
                    print("M3_FIREBASE_SPIKE retiredClientWritesDenied=\(denied ? "PASS" : "FAIL")")
                    self.nextStage()
                }
            }
        }

        private func nextStage() {
            let terminateAfterOperation = stage < 2
            let epoch = Int64(1_000 + Int64(stage) * 10)
            let bridge = FirebaseNativeBridge(host: "127.0.0.1", projectID: projectID)
            activeBridge = bridge
            bridge.signInAnonymously { [weak self] result in
                guard let self else { return }
                switch result {
                case .failure(let error):
                    print("M3_FIREBASE_SPIKE recreatedBridgeOperation=FAIL stage=\(self.stage) code=\((error as NSError).code)")
                    self.completion()
                case .success(let uid):
                    let path = "spikeEcho/\(uid)/documents/native-ios-recreated-\(self.stage)"
                    _ = bridge.writeDocument(
                        path: path,
                        fields: ["ownerUid": uid, "value": "recreated", "counter": "0"],
                        accountEpoch: epoch
                    ) { writeResult in
                        guard writeResult.failure == nil else {
                            print("M3_FIREBASE_SPIKE recreatedBridgeOperation=FAIL stage=\(self.stage) code=\(writeResult.failure?.code ?? "UNKNOWN")")
                            self.completion()
                            return
                        }
                        _ = bridge.getDocument(path: path, accountEpoch: epoch) { readResult in
                            let operated = readResult.document?.exists == true && readResult.document?.isFromCache == false
                            print("M3_FIREBASE_SPIKE recreatedBridgeOperation=\(operated ? "PASS" : "FAIL") stage=\(self.stage)")
                            self.assertRetiredEpochFenced(bridge: bridge, path: path)
                            self.operationsCompleted += 1
                            guard terminateAfterOperation else {
                                print("M3_FIREBASE_SPIKE repeatedRecreate=\(self.operationsCompleted >= 3 ? "PASS" : "FAIL")")
                                self.completion()
                                return
                            }
                            bridge.terminateAndClear { clearResult in
                                print("M3_FIREBASE_SPIKE recreatedBridgeTerminate=\(clearResult.failure == nil ? "PASS" : "FAIL") stage=\(self.stage)")
                                self.stage += 1
                                self.nextStage()
                            }
                        }
                    }
                }
            }
        }

        /// A callback requested with the retired generation's epoch must never be delivered by a newer
        /// bridge, so an old client can never reach the new instance's callbacks.
        private func assertRetiredEpochFenced(bridge: FirebaseNativeBridge, path: String) {
            var delivered = false
            let deadline = Date().addingTimeInterval(2)
            _ = bridge.getDocument(path: path, accountEpoch: retiredEpoch) { _ in
                delivered = true
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                print("M3_FIREBASE_SPIKE retiredEpochFenced=\(!delivered && Date() >= deadline ? "PASS" : "FAIL")")
            }
        }
    }

    /// Exercises two live clients of the same project before either is torn down, then repeats
    /// A/B teardown and C recreation in the same process using real Auth and Firestore operations.
    private final class OverlappingClientsProbe {
        private let projectID: String
        private let completion: () -> Void
        private var cycle = 0
        private var clients: [FirebaseNativeBridge] = []

        init(projectID: String, completion: @escaping () -> Void) {
            self.projectID = projectID
            self.completion = completion
            runCycle()
        }

        private func operate(_ bridge: FirebaseNativeBridge, epoch: Int64, label: String, then: @escaping (String?) -> Void) {
            bridge.signInAnonymously { result in
                guard case .success(let uid) = result else { then(nil); return }
                let path = "spikeEcho/\(uid)/documents/native-ios-overlap-\(label)"
                _ = bridge.writeDocument(path: path, fields: ["ownerUid": uid, "value": label, "counter": "0"], accountEpoch: epoch) { write in
                    guard write.failure == nil else { then(nil); return }
                    _ = bridge.getDocument(path: path, accountEpoch: epoch) { read in
                        then(read.document?.exists == true && read.document?.isFromCache == false ? path : nil)
                    }
                }
            }
        }

        private func runCycle() {
            guard cycle < 2 else { completion(); return }
            let a = FirebaseNativeBridge(projectID: projectID)
            let b = FirebaseNativeBridge(projectID: projectID)
            clients = [a, b]
            let aEpoch = Int64(2_000 + cycle * 10)
            let bEpoch = aEpoch + 1
            print("M3_FIREBASE_SPIKE overlapDistinctApps=\(a.debugAppName != b.debugAppName ? "PASS" : "FAIL") cycle=\(cycle)")
            operate(a, epoch: aEpoch, label: "a-\(cycle)") { [self] aPath in
                guard let aPath else { print("M3_FIREBASE_SPIKE overlapAOperation=FAIL cycle=\(cycle)"); completion(); return }
                print("M3_FIREBASE_SPIKE overlapAOperation=PASS cycle=\(cycle)")
                operate(b, epoch: bEpoch, label: "b-\(cycle)") { [self] bPath in
                    guard let bPath else { print("M3_FIREBASE_SPIKE overlapBOperation=FAIL cycle=\(cycle)"); completion(); return }
                    print("M3_FIREBASE_SPIKE overlapBOperation=PASS cycle=\(cycle)")
                    a.terminateAndClear { [self] cleared in
                        print("M3_FIREBASE_SPIKE overlapATerminate=\(cleared.failure == nil ? "PASS" : "FAIL") cycle=\(cycle)")
                        _ = b.getDocument(path: bPath, accountEpoch: bEpoch) { [self] liveRead in
                            print("M3_FIREBASE_SPIKE overlapBAfterA=\(liveRead.document?.exists == true && liveRead.document?.isFromCache == false ? "PASS" : "FAIL") cycle=\(cycle)")
                            _ = a.getDocument(path: aPath, accountEpoch: aEpoch) { [self] retiredRead in
                                print("M3_FIREBASE_SPIKE overlapARetired=\(retiredRead.failure?.code == "FAILED_PRECONDITION" && retiredRead.document == nil ? "PASS" : "FAIL") cycle=\(cycle)")
                                var lateDelivered = false
                                _ = b.getDocument(path: bPath, accountEpoch: aEpoch) { _ in lateDelivered = true }
                                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [self] in
                                    print("M3_FIREBASE_SPIKE overlapLateCallback=\(!lateDelivered ? "PASS" : "FAIL") cycle=\(cycle)")
                                    b.terminateAndClear { [self] bCleared in
                                        print("M3_FIREBASE_SPIKE overlapBTerminate=\(bCleared.failure == nil ? "PASS" : "FAIL") cycle=\(cycle)")
                                        let c = FirebaseNativeBridge(projectID: projectID)
                                        clients.append(c)
                                        print("M3_FIREBASE_SPIKE overlapFreshC=\(c.debugAppName != a.debugAppName && c.debugAppName != b.debugAppName ? "PASS" : "FAIL") cycle=\(cycle)")
                                        operate(c, epoch: bEpoch + 1, label: "c-\(cycle)") { [self] cPath in
                                            print("M3_FIREBASE_SPIKE overlapCOperation=\(cPath != nil ? "PASS" : "FAIL") cycle=\(cycle)")
                                            c.terminateAndClear { [self] cCleared in
                                                print("M3_FIREBASE_SPIKE overlapCTerminate=\(cCleared.failure == nil ? "PASS" : "FAIL") cycle=\(cycle)")
                                                cycle += 1
                                                runCycle()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /// Holds a write immediately before the lifecycle gate, retires its client, then checks that
    /// no SDK set slipped through. All waits run off the main queue, which delivers SDK callbacks.
    private final class IssuanceTeardownProbe {
        private let bridge = FirebaseNativeBridge(projectID: "demo-tillfailure-m3")
        private let completion: () -> Void
        private let reachedIssuance = DispatchSemaphore(value: 0)
        private let releaseIssuance = DispatchSemaphore(value: 0)
        private let writerReturned = DispatchSemaphore(value: 0)
        private let rejectionDelivered = DispatchSemaphore(value: 0)
        private let stateLock = NSLock()
        private var sdkSets = 0
        private var callbacks = 0
        private var rejection: NativeFirebaseUnitResult?

        init(completion: @escaping () -> Void) {
            self.completion = completion
            bridge.signInAnonymously { [weak self] result in
                guard let self else { return }
                guard case .success(let uid) = result else {
                    print("M3_FIREBASE_SPIKE issuanceRetiredWrite=FAIL")
                    completion()
                    return
                }
                start(uid: uid)
            }
        }

        private func start(uid: String) {
            let epoch: Int64 = 4_000
            let path = "spikeEcho/\(uid)/documents/native-ios-issuance-race"
            bridge.debugBeforeWriteIssuance = { [weak self] in
                guard let self else { return }
                reachedIssuance.signal()
                _ = releaseIssuance.wait(timeout: .now() + 20)
            }
            bridge.debugDidIssueWrite = { [weak self] in
                guard let self else { return }
                stateLock.lock()
                sdkSets += 1
                stateLock.unlock()
            }
            DispatchQueue.global().async { [self] in
                _ = bridge.writeDocument(path: path, fields: ["ownerUid": uid, "value": "must-not-issue"], accountEpoch: epoch) { [weak self] result in
                    guard let self else { return }
                    stateLock.lock()
                    callbacks += 1
                    rejection = result
                    stateLock.unlock()
                    rejectionDelivered.signal()
                }
                writerReturned.signal()
            }
            DispatchQueue.global().async { [self] in
                guard reachedIssuance.wait(timeout: .now() + 20) == .success else {
                    releaseIssuance.signal()
                    DispatchQueue.main.async {
                        print("M3_FIREBASE_SPIKE issuanceRetiredWrite=FAIL")
                        completion()
                    }
                    return
                }
                DispatchQueue.main.async { [self] in
                    bridge.terminateAndClear { [self] cleared in
                        releaseIssuance.signal()
                        DispatchQueue.global().async { [self] in
                            let returned = writerReturned.wait(timeout: .now() + 20) == .success
                            let rejected = rejectionDelivered.wait(timeout: .now() + 20) == .success
                            stateLock.lock()
                            let passed = returned && rejected && cleared.failure == nil && sdkSets == 0 && callbacks == 1 &&
                                rejection?.failure?.code == "FAILED_PRECONDITION" && rejection?.failure?.retryable == false
                            stateLock.unlock()
                            DispatchQueue.main.async { [self] in
                                print("M3_FIREBASE_SPIKE issuanceRetiredWrite=\(passed ? "PASS" : "FAIL")")
                                verifyFreshGeneration()
                            }
                        }
                    }
                }
            }
        }

        private func verifyFreshGeneration() {
            let replacement = FirebaseNativeBridge(projectID: "demo-tillfailure-m3")
            replacement.signInAnonymously { [self] result in
                guard case .success(let uid) = result else {
                    print("M3_FIREBASE_SPIKE issuanceFreshGeneration=FAIL")
                    completion()
                    return
                }
                let path = "spikeEcho/\(uid)/documents/native-ios-after-issuance-race"
                _ = replacement.writeDocument(path: path, fields: ["ownerUid": uid, "value": "fresh"], accountEpoch: 4_001) { [self] write in
                    guard write.failure == nil else {
                        print("M3_FIREBASE_SPIKE issuanceFreshGeneration=FAIL")
                        completion()
                        return
                    }
                    _ = replacement.getDocument(path: path, accountEpoch: 4_001) { [self] read in
                        let usable = read.document?.exists == true && read.document?.isFromCache == false
                        replacement.terminateAndClear { [self] cleared in
                            print("M3_FIREBASE_SPIKE issuanceFreshGeneration=\(usable && cleared.failure == nil ? "PASS" : "FAIL")")
                            completion()
                        }
                    }
                }
            }
        }
    }
}
#endif
