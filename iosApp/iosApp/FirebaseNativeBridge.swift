import CoreFoundation
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation
import Shared

/// Process-wide ownership of the named `FirebaseApp` instances used by the spike bridge.
///
/// A Firestore client that completed `terminate`/`clearPersistence` is permanently unusable, and
/// `Firestore.firestore(app:)` keeps returning that same terminated instance for as long as the app
/// object exists. The registry therefore issues one *generation* per project at a time:
///
///  - a generation is closed synchronously the moment teardown starts, so a bridge constructed
///    concurrently with (or after) teardown can never acquire the dying app or its Firestore/Auth
///    singletons;
///  - the next bridge configures a brand-new app with a unique name, so it always receives fresh SDK
///    instances, independent of when the retired app's asynchronous deletion finishes;
///  - the retired app is deleted only after its Firestore instance has been terminated and its
///    persistence cleared, releasing its resources without ever unblocking reuse of the dead
///    instances;
///  - an old bridge keeps its own (dead) references, so it can never operate on a newer generation.
private final class FirebaseAppGenerations {
    static let shared = FirebaseAppGenerations()

    final class Generation {
        let projectID: String
        let appName: String
        let app: FirebaseApp
        private let lock = NSLock()
        private var closed = false

        init(projectID: String, appName: String, app: FirebaseApp) {
            self.projectID = projectID
            self.appName = appName
            self.app = app
        }

        func close() {
            lock.lock()
            closed = true
            lock.unlock()
        }

        var isClosed: Bool {
            lock.lock()
            defer { lock.unlock() }
            return closed
        }
    }

    private let lock = NSLock()
    private var generations: [String: Generation] = [:]
    private var sequence: Int64 = 0

    /// Returns the live generation for the project, or configures a fresh one.
    func acquire(projectID: String) -> Generation {
        lock.lock()
        defer { lock.unlock() }
        if let live = generations[projectID], !live.isClosed {
            return live
        }
        sequence += 1
        let appName = "tillfailure-\(projectID)-\(sequence)"
        let options = FirebaseOptions(googleAppID: "1:1234567890:ios:0000000000000000", gcmSenderID: "1234567890")
        options.apiKey = "fake-emulator-api-key"
        options.projectID = projectID
        options.storageBucket = "\(projectID).appspot.com"
        FirebaseApp.configure(name: appName, options: options)
        guard let app = FirebaseApp.app(name: appName) else {
            preconditionFailure("Failed to configure the Firebase spike app \(appName)")
        }
        let generation = Generation(projectID: projectID, appName: appName, app: app)
        generations[projectID] = generation
        return generation
    }

    /// Closes the generation synchronously so no new bridge can be handed its instances. This runs
    /// before the SDK teardown starts and is idempotent.
    func close(_ generation: Generation) {
        lock.lock()
        generation.close()
        if generations[generation.projectID] === generation {
            generations.removeValue(forKey: generation.projectID)
        }
        lock.unlock()
    }

    /// Best-effort asynchronous deletion of the retired app once its instances are terminated.
    /// Failure needs no handling: the unique per-generation app name already guarantees that the dead
    /// instances can never be reissued, so cleanup is resource hygiene only.
    func delete(_ generation: Generation) {
        close(generation)
        generation.app.delete { _ in }
    }
}

final class FirebaseNativeBridge: NSObject, NativeFirebaseBridge {
    /// Thread-safe single-settlement gate for one-shot operations. Exactly one of the
    /// SDK completion, explicit cancellation, timeout, or global termination claims it,
    /// so a callback is delivered at most once and completed tokens are never retained.
    private final class OneShotGate {
        private let lock = NSLock()
        private var settled = false

        func claim() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            guard !settled else { return false }
            settled = true
            return true
        }
    }

    private let generation: FirebaseAppGenerations.Generation
    private let auth: Auth
    private let firestore: Firestore
    private let lock = NSLock()
    private var cancellations: [String: () -> Void] = [:]
    private var currentEpoch: Int64 = 0
    private var terminated = false

    init(host: String = "127.0.0.1", projectID: String = "demo-tillfailure-m3") {
        precondition(projectID.hasPrefix("demo-"), "The Firebase spike requires an emulator-only demo project")
        precondition(["127.0.0.1", "localhost"].contains(host), "The Firebase spike requires loopback")
        // One generation per project: a bridge constructed after a completed teardown configures a
        // fresh app (and therefore fresh Auth/Firestore instances) instead of reusing the terminated
        // singletons its predecessor retired.
        let current = FirebaseAppGenerations.shared.acquire(projectID: projectID)
        generation = current
        let app = current.app
        auth = Auth.auth(app: app)
        auth.useEmulator(withHost: host, port: 9099)
        firestore = Firestore.firestore(app: app)
        let settings = firestore.settings
        settings.host = "\(host):8080"
        settings.isSSLEnabled = false
        firestore.settings = settings
        super.init()
    }

    /// Fail-closed guard for every operation: once this bridge's teardown has run it must never touch
    /// the SDK again, and callers receive an explicit non-retryable failure instead of silence or a
    /// callback that could be mistaken for a newer generation's result.
    private var terminatedFailure: NativeFirebaseFailure? {
        lock.lock()
        defer { lock.unlock() }
        return terminated ? NativeFirebaseFailure(code: "FAILED_PRECONDITION", retryable: false) : nil
    }

    /// Delivers the terminal failure for an operation issued after this bridge was terminated. The
    /// token is deliberately not registered as a cancellation (registration settles immediately while
    /// terminated), and the account-epoch fence is still honored for a stale epoch.
    private func deliverTerminatedFailure(accountEpoch: Int64, deliver: @escaping () -> Void) -> String {
        let token = UUID().uuidString
        DispatchQueue.main.async { [weak self] in
            guard let self, self.epochAccepted(epoch: accountEpoch) else { return }
            deliver()
        }
        return token
    }

    private func epochAccepted(epoch: Int64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return epoch == currentEpoch
    }

    func observeSession(accountEpoch: Int64, callback_: @escaping (NativeFirebaseAuthState) -> Void) -> String {
        activate(epoch: accountEpoch)
        // A terminated bridge registers no listener: there is no live SDK instance left to observe.
        if terminatedFailure != nil { return UUID().uuidString }
        let handle = auth.addStateDidChangeListener { [weak self] _, user in
            guard self?.accepts(epoch: accountEpoch) == true else { return }
            callback_(NativeFirebaseAuthState(uid: user?.uid, isAnonymous: user?.isAnonymous == true))
        }
        return registerCancellation { [weak self] in self?.auth.removeStateDidChangeListener(handle) }
    }

    func getDocument(path: String, accountEpoch: Int64, callback_: @escaping (NativeFirebaseDocumentResult) -> Void) -> String {
        activate(epoch: accountEpoch)
        if let failure = terminatedFailure {
            return deliverTerminatedFailure(accountEpoch: accountEpoch) {
                callback_(NativeFirebaseDocumentResult(document: nil, failure: failure))
            }
        }
        let (token, gate) = beginOneShot()
        firestore.document(path).getDocument(source: .server) { [weak self] snapshot, error in
            guard let self else { return }
            self.deliverOneShot(token: token, gate: gate, accountEpoch: accountEpoch) {
                callback_(self.documentResult(snapshot: snapshot, error: error))
            }
        }
        return token
    }

    func listenDocument(path: String, accountEpoch: Int64, callback_: @escaping (NativeFirebaseDocumentResult) -> Void) -> String {
        activate(epoch: accountEpoch)
        // A terminated bridge registers no listener instead of attaching to a dead instance.
        if terminatedFailure != nil { return UUID().uuidString }
        let registration = firestore.document(path).addSnapshotListener(includeMetadataChanges: true) { [weak self] snapshot, error in
            guard self?.accepts(epoch: accountEpoch) == true else { return }
            callback_(self?.documentResult(snapshot: snapshot, error: error) ?? Self.unknownDocumentResult())
        }
        return registerCancellation { registration.remove() }
    }

    func writeDocument(path: String, fields: [String: String], accountEpoch: Int64, callback_: @escaping (NativeFirebaseUnitResult) -> Void) -> String {
        activate(epoch: accountEpoch)
        if let failure = terminatedFailure {
            return deliverTerminatedFailure(accountEpoch: accountEpoch) {
                callback_(NativeFirebaseUnitResult(failure: failure))
            }
        }
        let (token, gate) = beginOneShot()
        firestore.document(path).setData(fields) { [weak self] error in
            guard let self else { return }
            self.deliverOneShot(token: token, gate: gate, accountEpoch: accountEpoch) {
                callback_(NativeFirebaseUnitResult(failure: error.map(Self.mapFailure)))
            }
        }
        return token
    }

    func increment(path: String, field: String, by: Int64, accountEpoch: Int64, callback_: @escaping (NativeFirebaseDocumentResult) -> Void) -> String {
        activate(epoch: accountEpoch)
        if let failure = terminatedFailure {
            return deliverTerminatedFailure(accountEpoch: accountEpoch) {
                callback_(NativeFirebaseDocumentResult(document: nil, failure: failure))
            }
        }
        let (token, gate) = beginOneShot()
        let reference = firestore.document(path)
        firestore.runTransaction({ transaction, errorPointer -> Any? in
            do {
                let snapshot = try transaction.getDocument(reference)
                let current = try Self.counterStartValue(snapshot.get(field))
                guard let next = CounterValueContract.shared.addExact(left: current, right: by)?.int64Value else {
                    throw Self.counterFailure("Counter increment overflow for '\(field)'")
                }
                transaction.setData([field: next], forDocument: reference, merge: true)
                return nil
            } catch {
                errorPointer?.pointee = error as NSError
                return nil
            }
        }) { [weak self] _, error in
            guard let self else { return }
            if let error {
                self.deliverOneShot(token: token, gate: gate, accountEpoch: accountEpoch) {
                    callback_(NativeFirebaseDocumentResult(document: nil, failure: Self.mapFailure(error)))
                }
            } else {
                reference.getDocument(source: .server) { [weak self] snapshot, readError in
                    guard let self else { return }
                    self.deliverOneShot(token: token, gate: gate, accountEpoch: accountEpoch) {
                        callback_(self.documentResult(snapshot: snapshot, error: readError))
                    }
                }
            }
        }
        return token
    }

    func waitForPendingWrites(accountEpoch: Int64, timeoutMillis: Int64, callback_: @escaping (NativeFirebaseUnitResult) -> Void) -> String {
        precondition(timeoutMillis > 0, "Pending-write timeout must be positive")
        activate(epoch: accountEpoch)
        if let failure = terminatedFailure {
            return deliverTerminatedFailure(accountEpoch: accountEpoch) {
                callback_(NativeFirebaseUnitResult(failure: failure))
            }
        }
        let token = UUID().uuidString
        let gate = OneShotGate()
        let finish: (NativeFirebaseUnitResult) -> Void = { [weak self] result in
            guard let self else { return }
            self.deliverOneShot(token: token, gate: gate, accountEpoch: accountEpoch) {
                callback_(result)
            }
        }
        let timeout = DispatchWorkItem {
            finish(NativeFirebaseUnitResult(failure: NativeFirebaseFailure(code: "DEADLINE_EXCEEDED", retryable: true)))
        }
        registerCancellation(token: token) {
            timeout.cancel()
            finish(NativeFirebaseUnitResult(failure: NativeFirebaseFailure(code: "CANCELLED", retryable: true)))
        }
        DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(Int(timeoutMillis)), execute: timeout)
        firestore.waitForPendingWrites { error in
            timeout.cancel()
            finish(NativeFirebaseUnitResult(failure: error.map(Self.mapFailure)))
        }
        return token
    }

    func cancel(token: String) {
        let cancellation: (() -> Void)?
        lock.lock()
        cancellation = cancellations.removeValue(forKey: token)
        lock.unlock()
        cancellation?()
    }

    func terminateAndClear(callback_: @escaping (NativeFirebaseUnitResult) -> Void) {
        // Close the generation before touching the SDK: a bridge constructed concurrently with (or
        // after) this teardown must never be handed these instances, and this bridge is fenced at once.
        FirebaseAppGenerations.shared.close(generation)
        lock.lock()
        let firstTermination = !terminated
        terminated = true
        let outstanding = Array(cancellations.values)
        cancellations.removeAll()
        lock.unlock()
        outstanding.forEach { $0() }
        guard firstTermination else {
            // Repeated cleanup is deterministic and idempotent: the instances were already retired,
            // so there is nothing left to terminate or clear.
            callback_(NativeFirebaseUnitResult(failure: nil))
            return
        }
        // The teardown captures itself strongly: the completion must be delivered even if the caller
        // released its last reference while the SDK was finishing, otherwise a torn-down client would
        // report nothing at all. The closure (and the extra reference) ends with the teardown.
        firestore.terminate { [self] terminationError in
            guard terminationError == nil else {
                // Even a partial teardown retires the app: the dead instances must never be reissued.
                FirebaseAppGenerations.shared.delete(generation)
                callback_(NativeFirebaseUnitResult(failure: terminationError.map(Self.mapFailure)))
                return
            }
            firestore.clearPersistence { clearError in
                // Delete the retired app only after its Firestore instance is terminated and its
                // persistence cleared; a newer generation never depends on this cleanup.
                FirebaseAppGenerations.shared.delete(generation)
                callback_(NativeFirebaseUnitResult(failure: clearError.map(Self.mapFailure)))
            }
        }
    }

    func signInAnonymously(completion: @escaping (Result<String, Error>) -> Void) {
        if let failure = terminatedFailure {
            DispatchQueue.main.async {
                completion(.failure(NSError(
                    domain: "TillFailureFirebaseSpike",
                    code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "Firebase client is terminated (\(failure.code)); construct a new client"]
                )))
            }
            return
        }
        do {
            try auth.signOut()
        } catch {
            completion(.failure(error))
            return
        }
        auth.signInAnonymously { result, error in
            if let error { completion(.failure(error)) }
            else if let uid = result?.user.uid { completion(.success(uid)) }
            else { completion(.failure(NSError(domain: "TillFailureFirebaseSpike", code: 1))) }
        }
    }

    func disableNetwork(completion: @escaping (NativeFirebaseUnitResult) -> Void) {
        if let failure = terminatedFailure {
            DispatchQueue.main.async { completion(NativeFirebaseUnitResult(failure: failure)) }
            return
        }
        firestore.disableNetwork { error in
            completion(NativeFirebaseUnitResult(failure: error.map(Self.mapFailure)))
        }
    }

    func enableNetwork(completion: @escaping (NativeFirebaseUnitResult) -> Void) {
        if let failure = terminatedFailure {
            DispatchQueue.main.async { completion(NativeFirebaseUnitResult(failure: failure)) }
            return
        }
        firestore.enableNetwork { error in
            completion(NativeFirebaseUnitResult(failure: error.map(Self.mapFailure)))
        }
    }

    private func activate(epoch: Int64) {
        lock.lock()
        if epoch > currentEpoch { currentEpoch = epoch }
        lock.unlock()
    }

    private func accepts(epoch: Int64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return !terminated && epoch == currentEpoch
    }

    private func registerCancellation(_ cancellation: @escaping () -> Void) -> String {
        let token = UUID().uuidString
        registerCancellation(token: token, cancellation)
        return token
    }

    /// Preallocates the token and stores the one-shot cancellation entry exactly once
    /// before any SDK completion path is registered, so a completion can never race the
    /// registration and leave a retained closure behind.
    private func beginOneShot() -> (String, OneShotGate) {
        let token = UUID().uuidString
        let gate = OneShotGate()
        registerCancellation(token: token) { _ = gate.claim() }
        return (token, gate)
    }

    /// Claims the single settlement, removes the registry entry on success or failure
    /// alike, and delivers only while the account epoch still accepts callbacks.
    /// A completion after cancellation is suppressed; a cancellation after completion
    /// finds no entry and is a safe no-op.
    private func deliverOneShot(token: String, gate: OneShotGate, accountEpoch: Int64, deliver: () -> Void) {
        guard gate.claim() else { return }
        removeCancellation(token: token)
        guard accepts(epoch: accountEpoch) else { return }
        deliver()
    }

    private func registerCancellation(token: String, _ cancellation: @escaping () -> Void) {
        lock.lock()
        if terminated {
            lock.unlock()
            cancellation()
            return
        }
        cancellations[token] = cancellation
        lock.unlock()
    }

    private func removeCancellation(token: String) {
        lock.lock()
        cancellations.removeValue(forKey: token)
        lock.unlock()
    }

    #if DEBUG
    /// Test-only diagnostic for the Debug-gated spike harness; not compiled into release builds.
    var debugCancellationCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return cancellations.count
    }
    #endif

    private func documentResult(snapshot: DocumentSnapshot?, error: Error?) -> NativeFirebaseDocumentResult {
        if let error { return NativeFirebaseDocumentResult(document: nil, failure: Self.mapFailure(error)) }
        guard let snapshot else { return Self.unknownDocumentResult() }
        let fields = snapshot.data()?.mapValues { String(describing: $0) } ?? [:]
        return NativeFirebaseDocumentResult(
            document: NativeFirebaseDocument(
                path: snapshot.reference.path,
                fields: fields,
                exists: snapshot.exists,
                isFromCache: snapshot.metadata.isFromCache,
                hasPendingWrites: snapshot.metadata.hasPendingWrites
            ),
            failure: nil
        )
    }

    private static func unknownDocumentResult() -> NativeFirebaseDocumentResult {
        NativeFirebaseDocumentResult(document: nil, failure: NativeFirebaseFailure(code: "UNKNOWN", retryable: false))
    }

    private static let counterErrorDomain = "TillFailureCounterValue"

    private static func counterFailure(_ message: String) -> NSError {
        NSError(domain: counterErrorDomain, code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }

    private static func isCounterValueFailure(_ error: NSError) -> Bool {
        if error.domain == counterErrorDomain { return true }
        if let underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError, underlying.domain == counterErrorDomain {
            return true
        }
        return false
    }

    // Canonical shared counter contract: an integral value, a canonical signed integer string,
    // or a missing/null field starting from zero. Booleans, floating point, malformed strings,
    // and unsupported Firestore types are rejected instead of silently resetting the counter.
    private static func counterStartValue(_ stored: Any?) throws -> Int64 {
        guard let stored, !(stored is NSNull) else { return 0 }
        if let text = stored as? String {
            guard let parsed = CounterValueContract.shared.parseCanonicalInt64(text: text) else {
                throw counterFailure("Stored counter is not a canonical signed integer")
            }
            return parsed.int64Value
        }
        if let number = stored as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                throw counterFailure("Stored counter is a boolean")
            }
            if CFNumberIsFloatType(number) {
                throw counterFailure("Stored counter is not an integer")
            }
            return number.int64Value
        }
        throw counterFailure("Stored counter type is unsupported")
    }

    private static func mapFailure(_ error: Error) -> NativeFirebaseFailure {
        let nsError = error as NSError
        if isCounterValueFailure(nsError) { return NativeFirebaseFailure(code: "INVALID_ARGUMENT", retryable: false) }
        let code = FirestoreErrorCode.Code(rawValue: nsError.code)
        switch code {
        case .permissionDenied: return NativeFirebaseFailure(code: "PERMISSION_DENIED", retryable: false)
        case .unauthenticated: return NativeFirebaseFailure(code: "UNAUTHENTICATED", retryable: false)
        case .unavailable: return NativeFirebaseFailure(code: "UNAVAILABLE", retryable: true)
        case .cancelled: return NativeFirebaseFailure(code: "CANCELLED", retryable: true)
        case .deadlineExceeded: return NativeFirebaseFailure(code: "DEADLINE_EXCEEDED", retryable: true)
        case .aborted: return NativeFirebaseFailure(code: "CONFLICT", retryable: true)
        case .invalidArgument: return NativeFirebaseFailure(code: "INVALID_ARGUMENT", retryable: false)
        case .failedPrecondition: return NativeFirebaseFailure(code: "FAILED_PRECONDITION", retryable: false)
        default: return NativeFirebaseFailure(code: "UNKNOWN", retryable: false)
        }
    }
}
