import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation
import Shared

final class FirebaseNativeBridge: NSObject, NativeFirebaseBridge {
    private final class CallbackGate {
        var cancelled = false
    }

    private final class PendingWriteGate {
        private let lock = NSLock()
        private var completed = false

        func claim() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            guard !completed else { return false }
            completed = true
            return true
        }
    }

    private let auth: Auth
    private let firestore: Firestore
    private let lock = NSLock()
    private var cancellations: [String: () -> Void] = [:]
    private var currentEpoch: Int64 = 0
    private var terminated = false

    init(host: String = "127.0.0.1", projectID: String = "demo-tillfailure-m3") {
        precondition(projectID.hasPrefix("demo-"), "The Firebase spike requires an emulator-only demo project")
        precondition(["127.0.0.1", "localhost"].contains(host), "The Firebase spike requires loopback")
        let name = "tillfailure-\(projectID)"
        let app: FirebaseApp
        if let existing = FirebaseApp.app(name: name) {
            app = existing
        } else {
            let options = FirebaseOptions(googleAppID: "1:1234567890:ios:0000000000000000", gcmSenderID: "1234567890")
            options.apiKey = "fake-emulator-api-key"
            options.projectID = projectID
            options.storageBucket = "\(projectID).appspot.com"
            FirebaseApp.configure(name: name, options: options)
            app = FirebaseApp.app(name: name)!
        }
        auth = Auth.auth(app: app)
        auth.useEmulator(withHost: host, port: 9099)
        firestore = Firestore.firestore(app: app)
        let settings = firestore.settings
        settings.host = "\(host):8080"
        settings.isSSLEnabled = false
        firestore.settings = settings
        super.init()
    }

    func observeSession(accountEpoch: Int64, callback_: @escaping (NativeFirebaseAuthState) -> Void) -> String {
        activate(epoch: accountEpoch)
        let handle = auth.addStateDidChangeListener { [weak self] _, user in
            guard self?.accepts(epoch: accountEpoch) == true else { return }
            callback_(NativeFirebaseAuthState(uid: user?.uid, isAnonymous: user?.isAnonymous == true))
        }
        return registerCancellation { [weak self] in self?.auth.removeStateDidChangeListener(handle) }
    }

    func getDocument(path: String, accountEpoch: Int64, callback_: @escaping (NativeFirebaseDocumentResult) -> Void) -> String {
        activate(epoch: accountEpoch)
        let gate = CallbackGate()
        firestore.document(path).getDocument(source: .server) { [weak self] snapshot, error in
            guard !gate.cancelled, self?.accepts(epoch: accountEpoch) == true else { return }
            callback_(self?.documentResult(snapshot: snapshot, error: error) ?? Self.unknownDocumentResult())
        }
        return registerCancellation { gate.cancelled = true }
    }

    func listenDocument(path: String, accountEpoch: Int64, callback_: @escaping (NativeFirebaseDocumentResult) -> Void) -> String {
        activate(epoch: accountEpoch)
        let registration = firestore.document(path).addSnapshotListener(includeMetadataChanges: true) { [weak self] snapshot, error in
            guard self?.accepts(epoch: accountEpoch) == true else { return }
            callback_(self?.documentResult(snapshot: snapshot, error: error) ?? Self.unknownDocumentResult())
        }
        return registerCancellation { registration.remove() }
    }

    func writeDocument(path: String, fields: [String: String], accountEpoch: Int64, callback_: @escaping (NativeFirebaseUnitResult) -> Void) -> String {
        activate(epoch: accountEpoch)
        let gate = CallbackGate()
        firestore.document(path).setData(fields) { [weak self] error in
            guard !gate.cancelled, self?.accepts(epoch: accountEpoch) == true else { return }
            callback_(NativeFirebaseUnitResult(failure: error.map(Self.mapFailure)))
        }
        return registerCancellation { gate.cancelled = true }
    }

    func increment(path: String, field: String, by: Int64, accountEpoch: Int64, callback_: @escaping (NativeFirebaseDocumentResult) -> Void) -> String {
        activate(epoch: accountEpoch)
        let gate = CallbackGate()
        let reference = firestore.document(path)
        firestore.runTransaction({ transaction, errorPointer -> Any? in
            do {
                let snapshot = try transaction.getDocument(reference)
                let current = (snapshot.get(field) as? NSNumber)?.int64Value ?? 0
                transaction.setData([field: current + by], forDocument: reference, merge: true)
                return nil
            } catch {
                errorPointer?.pointee = error as NSError
                return nil
            }
        }) { [weak self] _, error in
            guard !gate.cancelled, self?.accepts(epoch: accountEpoch) == true else { return }
            if let error {
                callback_(NativeFirebaseDocumentResult(document: nil, failure: Self.mapFailure(error)))
            } else {
                reference.getDocument(source: .server) { snapshot, readError in
                    guard !gate.cancelled, self?.accepts(epoch: accountEpoch) == true else { return }
                    callback_(self?.documentResult(snapshot: snapshot, error: readError) ?? Self.unknownDocumentResult())
                }
            }
        }
        return registerCancellation { gate.cancelled = true }
    }

    func waitForPendingWrites(accountEpoch: Int64, timeoutMillis: Int64, callback_: @escaping (NativeFirebaseUnitResult) -> Void) -> String {
        precondition(timeoutMillis > 0, "Pending-write timeout must be positive")
        activate(epoch: accountEpoch)
        let gate = PendingWriteGate()
        let token = UUID().uuidString
        let finish: (NativeFirebaseUnitResult) -> Void = { [weak self] result in
            guard gate.claim() else { return }
            self?.removeCancellation(token: token)
            guard self?.accepts(epoch: accountEpoch) == true else { return }
            callback_(result)
        }
        let timeout = DispatchWorkItem {
            finish(NativeFirebaseUnitResult(failure: NativeFirebaseFailure(code: "DEADLINE_EXCEEDED", retryable: true)))
        }
        DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(Int(timeoutMillis)), execute: timeout)
        firestore.waitForPendingWrites { error in
            timeout.cancel()
            finish(NativeFirebaseUnitResult(failure: error.map(Self.mapFailure)))
        }
        registerCancellation(token: token) {
            timeout.cancel()
            finish(NativeFirebaseUnitResult(failure: NativeFirebaseFailure(code: "CANCELLED", retryable: true)))
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
        lock.lock()
        terminated = true
        let outstanding = Array(cancellations.values)
        cancellations.removeAll()
        lock.unlock()
        outstanding.forEach { $0() }
        firestore.terminate { [weak self] terminationError in
            guard terminationError == nil else {
                callback_(NativeFirebaseUnitResult(failure: terminationError.map(Self.mapFailure)))
                return
            }
            self?.firestore.clearPersistence { clearError in
                callback_(NativeFirebaseUnitResult(failure: clearError.map(Self.mapFailure)))
            }
        }
    }

    func signInAnonymously(completion: @escaping (Result<String, Error>) -> Void) {
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
        firestore.disableNetwork { error in
            completion(NativeFirebaseUnitResult(failure: error.map(Self.mapFailure)))
        }
    }

    func enableNetwork(completion: @escaping (NativeFirebaseUnitResult) -> Void) {
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

    private static func mapFailure(_ error: Error) -> NativeFirebaseFailure {
        let nsError = error as NSError
        let code = FirestoreErrorCode.Code(rawValue: nsError.code)
        switch code {
        case .permissionDenied: return NativeFirebaseFailure(code: "PERMISSION_DENIED", retryable: false)
        case .unauthenticated: return NativeFirebaseFailure(code: "UNAUTHENTICATED", retryable: false)
        case .unavailable: return NativeFirebaseFailure(code: "UNAVAILABLE", retryable: true)
        case .cancelled: return NativeFirebaseFailure(code: "CANCELLED", retryable: true)
        case .deadlineExceeded: return NativeFirebaseFailure(code: "DEADLINE_EXCEEDED", retryable: true)
        case .aborted: return NativeFirebaseFailure(code: "CONFLICT", retryable: true)
        case .invalidArgument: return NativeFirebaseFailure(code: "INVALID_ARGUMENT", retryable: false)
        default: return NativeFirebaseFailure(code: "UNKNOWN", retryable: false)
        }
    }
}
