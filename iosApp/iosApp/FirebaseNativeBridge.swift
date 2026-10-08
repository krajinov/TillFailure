import CoreFoundation
import CryptoKit
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation
import Shared

/// Process-wide ownership of the named `FirebaseApp` instances used by the spike bridge.
///
/// A Firestore client that completed `terminate`/`clearPersistence` is permanently unusable, and
/// `Firestore.firestore(app:)` keeps returning that same terminated instance for as long as the app
/// object exists. The registry therefore issues one *generation* per bridge:
///
///  - a generation is closed synchronously the moment teardown starts, so a bridge constructed
///    concurrently with (or after) teardown can never acquire the dying app or its Firestore/Auth
///    singletons;
///  - overlapping bridges for the same project have distinct Auth and Firestore instances, so
///    terminating one cannot invalidate the other;
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
    private var sequence: Int64 = 0

    /// Configures a uniquely named app for this bridge, even when another bridge is live.
    func acquire(projectID: String, preferredName: String? = nil) -> Generation {
        lock.lock()
        defer { lock.unlock() }
        sequence += 1
        let appName = preferredName ?? "tillfailure-\(projectID)-\(sequence)"
        let options = FirebaseOptions(googleAppID: "1:1234567890:ios:0000000000000000", gcmSenderID: "1234567890")
        options.apiKey = "fake-emulator-api-key"
        options.projectID = projectID
        options.storageBucket = "\(projectID).appspot.com"
        FirebaseApp.configure(name: appName, options: options)
        guard let app = FirebaseApp.app(name: appName) else {
            preconditionFailure("Failed to configure the Firebase spike app \(appName)")
        }
        return Generation(projectID: projectID, appName: appName, app: app)
    }

    /// Closes the generation synchronously so no new bridge can be handed its instances. This runs
    /// before the SDK teardown starts and is idempotent.
    func close(_ generation: Generation) {
        lock.lock()
        generation.close()
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

final class FirebaseNativeBridge: NSObject, NativeIdentityBridge {
    private static let identityPinLock = NSLock()
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
    // A consumer may synchronously start another bridge operation in its callback.
    private let lock = NSRecursiveLock()
    private var cancellations: [String: () -> Void] = [:]
    private var currentEpoch: Int64 = 0
    private var terminated = false
    private var accountFrozen = false
    private var successor: FirebaseNativeBridge?
    private let identityPinKey = "tillfailure.identityPin.v1.sha256"
    private let switchUidKey = "tillfailure.departure.v1.uid"
    private let switchEpochKey = "tillfailure.departure.v1.epoch"
    private let lastEpochKey = "tillfailure.departure.v1.lastEpoch"
    private let productAppNameKey = "tillfailure.identity.productAppName.v1"
    private let emulatorHost: String
    private let productMemoryCache: Bool
    private let identityDefaults: UserDefaults
    private let transientProductGeneration: Bool
    /// Process-level departure owner for this bridge generation. The host retains one bridge for the
    /// process, so an in-progress pre-marker departure stays visible to a recreated account graph.
    let ownership = CleanDepartureOwnership()

    func hasPinnedIdentity() -> Bool {
        Self.identityPinLock.lock()
        defer { Self.identityPinLock.unlock() }
        return identityDefaults.object(forKey: identityPinKey) != nil
    }

    func claimPinnedIdentity(uid: String) -> Bool {
        Self.identityPinLock.lock()
        defer { Self.identityPinLock.unlock() }
        let digest = SHA256.hash(data: Data(uid.utf8)).map { String(format: "%02x", $0) }.joined()
        if let existing = identityDefaults.string(forKey: identityPinKey) { return existing == digest }
        if identityDefaults.object(forKey: identityPinKey) != nil { return false }
        identityDefaults.set(digest, forKey: identityPinKey)
        return identityDefaults.synchronize()
    }

    #if DEBUG
    var debugAppName: String { generation.appName }
    var debugCurrentUid: String? { auth.currentUser?.uid }
    var debugBeforeWriteIssuance: (() -> Void)?
    var debugDidIssueWrite: (() -> Void)?
    #endif

    init(host: String = "127.0.0.1", projectID: String = "demo-tillfailure-m3", productMemoryCache: Bool = false,
         identityDefaults: UserDefaults = .standard, transientProductGeneration: Bool = false) {
        precondition(projectID.hasPrefix("demo-"), "The Firebase spike requires an emulator-only demo project")
        precondition(["127.0.0.1", "localhost"].contains(host), "The Firebase spike requires loopback")
        emulatorHost = host
        self.productMemoryCache = productMemoryCache
        self.identityDefaults = identityDefaults
        self.transientProductGeneration = transientProductGeneration
        // One generation per bridge: an overlapping bridge and a later recreation both receive
        // fresh Auth/Firestore instances independent of this bridge's teardown.
        let preferredName: String?
        if productMemoryCache {
            Self.identityPinLock.lock()
            if let existing = identityDefaults.string(forKey: productAppNameKey) {
                preferredName = transientProductGeneration ? "\(existing)-retry-\(UUID().uuidString)" : existing
            } else {
                let created = "tillfailure-\(projectID)-product-\(UUID().uuidString)"
                identityDefaults.set(created, forKey: productAppNameKey)
                precondition(identityDefaults.synchronize(), "Cannot persist product Firebase generation")
                preferredName = transientProductGeneration ? "\(created)-retry-\(UUID().uuidString)" : created
            }
            Self.identityPinLock.unlock()
        } else {
            preferredName = nil
        }
        let current = FirebaseAppGenerations.shared.acquire(projectID: projectID, preferredName: preferredName)
        generation = current
        let app = current.app
        auth = Auth.auth(app: app)
        auth.useEmulator(withHost: host, port: 9099)
        firestore = Firestore.firestore(app: app)
        let settings = firestore.settings
        settings.host = "\(host):8080"
        settings.isSSLEnabled = false
        if productMemoryCache { settings.cacheSettings = MemoryCacheSettings() }
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

    /// The live check, cancellation registration, and SDK call are one synchronous lifecycle
    /// operation. Teardown cannot retire the bridge between these steps; no async completion holds
    /// the lock. NSRecursiveLock permits SDK callbacks that happen inline to settle their token.
    private func issueIfLive<T>(_ issue: () throws -> T) rethrows -> T? {
        lock.lock()
        defer { lock.unlock() }
        guard !terminated else { return nil }
        return try issue()
    }

    private func issueIfWritable<T>(_ issue: () throws -> T) rethrows -> T? {
        lock.lock()
        defer { lock.unlock() }
        guard !terminated && !accountFrozen else { return nil }
        return try issue()
    }

    func readDepartureMarker() -> NativeDepartureMarkerRead {
        Self.identityPinLock.lock()
        defer { Self.identityPinLock.unlock() }
        let uid = identityDefaults.string(forKey: switchUidKey)
        let epoch = identityDefaults.object(forKey: switchEpochKey) as? Int64 ?? 0
        let readable = (uid == nil && epoch == 0) || (uid != nil && epoch > 0)
        return NativeDepartureMarkerRead(uid: uid, epoch: epoch, readable: readable)
    }

    func freezeAccount(uid: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !terminated else { return false }
        let marker = readDepartureMarker()
        guard marker.readable else { return false }
        if accountFrozen { return marker.uid == uid }
        if marker.uid == nil && auth.currentUser?.uid != uid { return false }
        if marker.uid != nil && (marker.uid != uid || (auth.currentUser?.uid != nil && auth.currentUser?.uid != uid)) {
            return false
        }
        accountFrozen = true
        return true
    }

    func isRetired() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return terminated
    }

    func activeBridge() -> NativeIdentityBridge {
        lock.lock()
        let next = successor
        lock.unlock()
        return next?.activeBridge() ?? self
    }

    func unfreezeAccount(uid: String) {
        lock.lock()
        accountFrozen = false
        lock.unlock()
    }

    func persistDepartureMarker(uid: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        Self.identityPinLock.lock()
        defer { Self.identityPinLock.unlock() }
        guard accountFrozen, identityDefaults.object(forKey: switchUidKey) == nil else { return false }
        let last = identityDefaults.object(forKey: lastEpochKey) as? Int64 ?? 0
        guard last < Int64.max else { return false }
        identityDefaults.set(uid, forKey: switchUidKey)
        identityDefaults.set(last + 1, forKey: switchEpochKey)
        identityDefaults.set(last + 1, forKey: lastEpochKey)
        return identityDefaults.synchronize()
    }

    func fenceDepartureCallbacks() {
        lock.lock()
        currentEpoch += 1
        let outstanding = Array(cancellations.values)
        cancellations.removeAll()
        lock.unlock()
        outstanding.forEach { $0() }
    }

    func signOutProduct(callback: @escaping (NativeFirebaseUnitResult) -> Void) {
        do {
            try auth.signOut()
            callback(NativeFirebaseUnitResult(failure: nil))
        } catch {
            callback(NativeFirebaseUnitResult(failure: Self.mapAuthFailure(error)))
        }
    }

    func completeDepartureMarker(uid: String) -> Bool {
        Self.identityPinLock.lock()
        defer { Self.identityPinLock.unlock() }
        guard identityDefaults.string(forKey: switchUidKey) == uid else { return false }
        let digest = SHA256.hash(data: Data(uid.utf8)).map { String(format: "%02x", $0) }.joined()
        if let pin = identityDefaults.string(forKey: identityPinKey), pin != digest { return false }
        identityDefaults.removeObject(forKey: identityPinKey)
        guard identityDefaults.synchronize() else { return false }
        if productMemoryCache {
            identityDefaults.set("tillfailure-\(generation.projectID)-product-\(UUID().uuidString)",
                                 forKey: productAppNameKey)
            guard identityDefaults.synchronize() else { return false }
        }
        identityDefaults.removeObject(forKey: switchUidKey)
        identityDefaults.removeObject(forKey: switchEpochKey)
        return identityDefaults.synchronize()
    }

    func replacementBridge() -> NativeIdentityBridge {
        let fresh = FirebaseNativeBridge(host: emulatorHost, projectID: generation.projectID,
                                         productMemoryCache: productMemoryCache, identityDefaults: identityDefaults,
                                         transientProductGeneration: readDepartureMarker().uid != nil)
        lock.lock()
        successor = fresh
        lock.unlock()
        return fresh
    }

    private func deliverIfLive(_ deliver: () -> Void) {
        lock.lock()
        defer { lock.unlock() }
        if !terminated { deliver() }
    }

    /// Delivers the terminal failure for an operation issued after this bridge was terminated. The
    /// token is deliberately not registered as a cancellation (registration settles immediately while
    /// terminated), and the account-epoch fence is still honored for a stale epoch.
    private func deliverTerminatedFailure(accountEpoch: Int64, deliver: @escaping () -> Void) -> String {
        let token = UUID().uuidString
        DispatchQueue.main.async { [weak self] in
            self?.deliverIfEpochMatches(epoch: accountEpoch, allowTerminated: true, deliver)
        }
        return token
    }

    private func deliverIfEpochMatches(epoch: Int64, allowTerminated: Bool = false, _ deliver: () -> Void) {
        lock.lock()
        defer { lock.unlock() }
        if epoch == currentEpoch && (allowTerminated || !terminated) { deliver() }
    }

    func observeSession(accountEpoch: Int64, callback_: @escaping (NativeFirebaseAuthState) -> Void) -> String {
        activate(epoch: accountEpoch)
        // A terminated bridge registers no listener: there is no live SDK instance left to observe.
        return issueIfLive {
            let handle = auth.addStateDidChangeListener { [weak self] _, user in
                self?.deliverIfEpochMatches(epoch: accountEpoch) {
                    callback_(NativeFirebaseAuthState(uid: user?.uid, isAnonymous: user?.isAnonymous == true,
                        email: user?.email, emailVerified: user?.isEmailVerified == true))
                }
            }
            return registerCancellation { [weak self] in self?.auth.removeStateDidChangeListener(handle) }
        } ?? UUID().uuidString
    }

    func getDocument(path: String, accountEpoch: Int64, callback_: @escaping (NativeFirebaseDocumentResult) -> Void) -> String {
        activate(epoch: accountEpoch)
        if let token = issueIfLive({
            let (token, gate) = beginOneShot()
            firestore.document(path).getDocument(source: .server) { [weak self] snapshot, error in
                guard let self else { return }
                self.deliverOneShot(token: token, gate: gate, accountEpoch: accountEpoch) {
                    callback_(self.documentResult(snapshot: snapshot, error: error))
                }
            }
            return token
        }) { return token }
        let failure = terminatedFailure!
        return deliverTerminatedFailure(accountEpoch: accountEpoch) {
            callback_(NativeFirebaseDocumentResult(document: nil, failure: failure))
        }
    }

    func signIn(email: String, password: String, accountEpoch: Int64, callback: @escaping (NativeFirebaseUnitResult) -> Void) -> String {
        activate(epoch: accountEpoch)
        if let token = issueIfWritable({
            let (token, gate) = beginOneShot()
            auth.signIn(withEmail: email, password: password) { [weak self] _, error in
                self?.deliverOneShot(token: token, gate: gate, accountEpoch: accountEpoch) {
                    callback(NativeFirebaseUnitResult(failure: error.map(Self.mapAuthFailure)))
                }
            }
            return token
        }) { return token }
        return deliverTerminatedFailure(accountEpoch: accountEpoch) {
            callback(NativeFirebaseUnitResult(failure: self.terminatedFailure ?? NativeFirebaseFailure(code: "FAILED_PRECONDITION", retryable: false)))
        }
    }

    func refreshSession(accountEpoch: Int64, callback: @escaping (NativeFirebaseSessionRefresh) -> Void) -> String {
        activate(epoch: accountEpoch)
        guard let user = auth.currentUser else {
            return deliverTerminatedFailure(accountEpoch: accountEpoch) {
                callback(NativeFirebaseSessionRefresh(uid: nil, isAnonymous: false, email: nil, emailVerified: false,
                    failure: NativeFirebaseFailure(code: "UNAUTHENTICATED", retryable: false)))
            }
        }
        if let token = issueIfLive({
            let (token, gate) = beginOneShot()
            user.getIDTokenForcingRefresh(true) { [weak self] _, error in
                guard let self else { return }
                self.deliverOneShot(token: token, gate: gate, accountEpoch: accountEpoch) {
                    // Read the freshly refreshed verification state; the observed session may be
                    // cached and is not authority for the unverified-email gate.
                    let current = self.auth.currentUser
                    callback(NativeFirebaseSessionRefresh(
                        uid: current?.uid, isAnonymous: current?.isAnonymous == true,
                        email: current?.email, emailVerified: current?.isEmailVerified == true,
                        failure: error.map(Self.mapAuthFailure)))
                }
            }
            return token
        }) { return token }
        return deliverTerminatedFailure(accountEpoch: accountEpoch) {
            callback(NativeFirebaseSessionRefresh(uid: nil, isAnonymous: false, email: nil, emailVerified: false,
                failure: self.terminatedFailure ?? NativeFirebaseFailure(code: "FAILED_PRECONDITION", retryable: false)))
        }
    }

    func discoverMemberships(uid: String, accountEpoch: Int64, callback: @escaping (NativeMembershipDiscoveryResult) -> Void) -> String {
        activate(epoch: accountEpoch)
        if let token = issueIfLive({
            let (token, gate) = beginOneShot()
            firestore.collectionGroup("memberships")
                .whereField("userId", isEqualTo: uid)
                .whereField("status", isEqualTo: "active")
                .whereField("schemaVersion", isEqualTo: 1)
                .limit(to: 20)
                .getDocuments(source: .server) { [weak self] snapshot, error in
                    guard let self else { return }
                    self.deliverOneShot(token: token, gate: gate, accountEpoch: accountEpoch) {
                        callback(NativeMembershipDiscoveryResult(
                            documents: snapshot?.documents.map { self.nativeDocument($0) },
                            failure: error.map(Self.mapFailure)
                        ))
                    }
                }
            return token
        }) { return token }
        return deliverTerminatedFailure(accountEpoch: accountEpoch) {
            callback(NativeMembershipDiscoveryResult(documents: nil, failure: self.terminatedFailure))
        }
    }

    func listenDocument(path: String, accountEpoch: Int64, callback_: @escaping (NativeFirebaseDocumentResult) -> Void) -> String {
        activate(epoch: accountEpoch)
        // A terminated bridge registers no listener instead of attaching to a dead instance.
        return issueIfLive {
            let registration = firestore.document(path).addSnapshotListener(includeMetadataChanges: true) { [weak self] snapshot, error in
                self?.deliverIfEpochMatches(epoch: accountEpoch) {
                    callback_(self?.documentResult(snapshot: snapshot, error: error) ?? Self.unknownDocumentResult())
                }
            }
            return registerCancellation { registration.remove() }
        } ?? UUID().uuidString
    }

    func writeDocument(path: String, fields: [String: String], accountEpoch: Int64, callback_: @escaping (NativeFirebaseUnitResult) -> Void) -> String {
        activate(epoch: accountEpoch)
        #if DEBUG
        debugBeforeWriteIssuance?()
        #endif
        if let token = issueIfWritable({
            let (token, gate) = beginOneShot()
            firestore.document(path).setData(fields) { [weak self] error in
                guard let self else { return }
                self.deliverOneShot(token: token, gate: gate, accountEpoch: accountEpoch) {
                    callback_(NativeFirebaseUnitResult(failure: error.map(Self.mapFailure)))
                }
            }
            #if DEBUG
            debugDidIssueWrite?()
            #endif
            return token
        }) { return token }
        let failure = terminatedFailure ?? NativeFirebaseFailure(code: "FAILED_PRECONDITION", retryable: false)
        return deliverTerminatedFailure(accountEpoch: accountEpoch) {
            callback_(NativeFirebaseUnitResult(failure: failure))
        }
    }

    func increment(path: String, field: String, by: Int64, accountEpoch: Int64, callback_: @escaping (NativeFirebaseDocumentResult) -> Void) -> String {
        activate(epoch: accountEpoch)
        if let token = issueIfWritable({
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
                    _ = self.issueIfLive {
                        reference.getDocument(source: .server) { [weak self] snapshot, readError in
                            guard let self else { return }
                            self.deliverOneShot(token: token, gate: gate, accountEpoch: accountEpoch) {
                                callback_(self.documentResult(snapshot: snapshot, error: readError))
                            }
                        }
                        return true
                    }
                }
            }
            return token
        }) { return token }
        let failure = terminatedFailure ?? NativeFirebaseFailure(code: "FAILED_PRECONDITION", retryable: false)
        return deliverTerminatedFailure(accountEpoch: accountEpoch) {
            callback_(NativeFirebaseDocumentResult(document: nil, failure: failure))
        }
    }

    func waitForPendingWrites(accountEpoch: Int64, timeoutMillis: Int64, callback_: @escaping (NativeFirebaseUnitResult) -> Void) -> String {
        precondition(timeoutMillis > 0, "Pending-write timeout must be positive")
        activate(epoch: accountEpoch)
        if let token = issueIfLive({
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
        }) { return token }
        let failure = terminatedFailure!
        return deliverTerminatedFailure(accountEpoch: accountEpoch) {
            callback_(NativeFirebaseUnitResult(failure: failure))
        }
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
        lock.lock()
        let firstTermination = !terminated
        terminated = true
        let outstanding = Array(cancellations.values)
        cancellations.removeAll()
        lock.unlock()
        FirebaseAppGenerations.shared.close(generation)
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
                FirebaseAppGenerations.shared.delete(self.generation)
                callback_(NativeFirebaseUnitResult(failure: terminationError.map(Self.mapFailure)))
                return
            }
            firestore.clearPersistence { clearError in
                // Delete the retired app only after its Firestore instance is terminated and its
                // persistence cleared; a newer generation never depends on this cleanup.
                FirebaseAppGenerations.shared.delete(self.generation)
                callback_(NativeFirebaseUnitResult(failure: clearError.map(Self.mapFailure)))
            }
        }
    }

    func signInAnonymously(completion: @escaping (Result<String, Error>) -> Void) {
        do {
            let issued = try issueIfWritable {
                try auth.signOut()
                auth.signInAnonymously { [weak self] result, error in
                    self?.deliverIfLive {
                        if let error { completion(.failure(error)) }
                        else if let uid = result?.user.uid { completion(.success(uid)) }
                        else { completion(.failure(NSError(domain: "TillFailureFirebaseSpike", code: 1))) }
                    }
                }
                return true
            }
            if issued != nil { return }
        } catch {
            completion(.failure(error))
            return
        }
        let failure = terminatedFailure ?? NativeFirebaseFailure(code: "FAILED_PRECONDITION", retryable: false)
        DispatchQueue.main.async {
            completion(.failure(NSError(
                domain: "TillFailureFirebaseSpike",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Firebase client is terminated (\(failure.code)); construct a new client"]
            )))
        }
    }

    func disableNetwork(completion: @escaping (NativeFirebaseUnitResult) -> Void) {
        if issueIfLive({
            firestore.disableNetwork { [weak self] error in
                self?.deliverIfLive {
                    completion(NativeFirebaseUnitResult(failure: error.map(Self.mapFailure)))
                }
            }
            return true
        }) != nil { return }
        let failure = terminatedFailure!
        DispatchQueue.main.async { completion(NativeFirebaseUnitResult(failure: failure)) }
    }

    func enableNetwork(completion: @escaping (NativeFirebaseUnitResult) -> Void) {
        if issueIfLive({
            firestore.enableNetwork { [weak self] error in
                self?.deliverIfLive {
                    completion(NativeFirebaseUnitResult(failure: error.map(Self.mapFailure)))
                }
            }
            return true
        }) != nil { return }
        let failure = terminatedFailure!
        DispatchQueue.main.async { completion(NativeFirebaseUnitResult(failure: failure)) }
    }

    private func activate(epoch: Int64) {
        lock.lock()
        if epoch > currentEpoch { currentEpoch = epoch }
        lock.unlock()
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
        deliverIfEpochMatches(epoch: accountEpoch, deliver)
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
        return NativeFirebaseDocumentResult(document: nativeDocument(snapshot), failure: nil)
    }

    private func nativeDocument(_ snapshot: DocumentSnapshot) -> NativeFirebaseDocument {
        NativeFirebaseDocument(
            path: snapshot.reference.path,
            fields: snapshot.data()?.mapValues { String(describing: $0) } ?? [:],
            exists: snapshot.exists,
            isFromCache: snapshot.metadata.isFromCache,
            hasPendingWrites: snapshot.metadata.hasPendingWrites
        )
    }

    private static func mapAuthFailure(_ error: Error) -> NativeFirebaseFailure {
        let nsError = error as NSError
        if nsError.domain == AuthErrorDomain {
            switch nsError.code {
            case 17004, 17008, 17009, 17011:
                return NativeFirebaseFailure(code: "INVALID_CREDENTIALS", retryable: false)
            case 17005, 17017, 17021:
                return NativeFirebaseFailure(code: "UNAUTHENTICATED", retryable: false)
            case 17020:
                return NativeFirebaseFailure(code: "UNAVAILABLE", retryable: true)
            default: break
            }
        }
        return NativeFirebaseFailure(code: "UNKNOWN", retryable: false)
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
