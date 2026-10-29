import CoreFoundation
import CryptoKit
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import FirebaseFunctions
import Foundation
import Network
import Shared
import Security

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
    private var invitationAuthEpoch: Int64 = 0
    private var invitationUid: String?
    private var invitationAuthHandle: AuthStateDidChangeListenerHandle?
    private var invitationInFlight = false
    let invitationChanges = InvitationChanges()
    #if DEBUG
    var debugBeforeInvitationTransport: ((@escaping () -> Void) -> Void)?
    var debugBeforeInvitationCompletion: ((@escaping () -> Void) -> Void)?
    #endif
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
    var debugReplacementBridge: FirebaseNativeBridge? { successor }
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
        #if DEBUG
        let authPort = Int(ProcessInfo.processInfo.environment["TF_AUTH_EMULATOR_PORT"] ?? "") ?? 9099
        let firestorePort = Int(ProcessInfo.processInfo.environment["TF_FIRESTORE_EMULATOR_PORT"] ?? "") ?? 8080
        #else
        let authPort = 9099
        let firestorePort = 8080
        #endif
        auth.useEmulator(withHost: host, port: authPort)
        firestore = Firestore.firestore(app: app)
        let settings = firestore.settings
        settings.host = "\(host):\(firestorePort)"
        settings.isSSLEnabled = false
        if productMemoryCache { settings.cacheSettings = MemoryCacheSettings() }
        firestore.settings = settings
        super.init()
        invitationUid = auth.currentUser?.uid
        invitationAuthHandle = auth.addStateDidChangeListener { [weak self] _, user in
            guard let self else { return }
            self.lock.lock()
            defer { self.lock.unlock() }
            if self.invitationUid != user?.uid {
                self.invitationUid = user?.uid
                self.invitationAuthEpoch += 1
            }
        }
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

    func withRecoveryLock(operation: () -> Void) {
        lock.lock()
        defer { lock.unlock() }
        guard !terminated else { return }
        operation()
    }

    func withWritableAccount(uid: String, operation: () -> KotlinBoolean) -> Bool {
        issueIfWritable {
            guard auth.currentUser?.uid == uid else { return false }
            return operation().boolValue
        } ?? false
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

    func hasInvitationInFlight() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return invitationInFlight
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
        if let handle = invitationAuthHandle { auth.removeStateDidChangeListener(handle) }
        invitationAuthHandle = nil
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

    private final class ConnectivityGate {
        private let lock = NSLock()
        private var value = false
        var cancelled: Bool {
            get { lock.lock(); defer { lock.unlock() }; return value }
            set { lock.lock(); value = newValue; lock.unlock() }
        }
    }

    func observeConnectivity(callback: @escaping (KotlinBoolean) -> Void) -> String {
        let monitor = NWPathMonitor()
        let token = UUID().uuidString
        let gate = ConnectivityGate()
        monitor.pathUpdateHandler = { [weak self] path in
            let connected = path.status == .satisfied
            DispatchQueue.main.async {
                guard !gate.cancelled else { return }
                self?.deliverIfEpochMatches(epoch: 0) { callback(KotlinBoolean(bool: connected)) }
            }
        }
        _ = issueIfLive {
            registerCancellation(token: token) { gate.cancelled = true; monitor.cancel() }
            monitor.start(queue: DispatchQueue(label: "tillfailure.connectivity"))
        }
        return token
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
        guard !hasPinnedIdentity(), auth.currentUser == nil else {
            return deliverTerminatedFailure(accountEpoch: accountEpoch) {
                callback(NativeFirebaseUnitResult(failure: NativeFirebaseFailure(code: "FAILED_PRECONDITION", retryable: false)))
            }
        }
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
                if let error {
                    self.deliverOneShot(token: token, gate: gate, accountEpoch: accountEpoch) {
                        callback(NativeFirebaseSessionRefresh(uid: nil, isAnonymous: false, email: nil, emailVerified: false,
                            failure: Self.mapAuthFailure(error)))
                    }
                    return
                }
                // `getIDTokenForcingRefresh` fetches only a token; `reload` is the operation that
                // refreshes user profile data such as email verification. Reload before reading the
                // user so an out-of-band verification change (in either direction) is observed now.
                user.reload { [weak self] reloadError in
                    guard let self else { return }
                    self.deliverOneShot(token: token, gate: gate, accountEpoch: accountEpoch) {
                        callback(NativeFirebaseSessionRefresh(
                            uid: user.uid, isAnonymous: user.isAnonymous, email: user.email,
                            emailVerified: user.isEmailVerified,
                            failure: reloadError.map(Self.mapAuthFailure)))
                    }
                }
            }
            return token
        }) { return token }
        return deliverTerminatedFailure(accountEpoch: accountEpoch) {
            callback(NativeFirebaseSessionRefresh(uid: nil, isAnonymous: false, email: nil, emailVerified: false,
                failure: self.terminatedFailure ?? NativeFirebaseFailure(code: "FAILED_PRECONDITION", retryable: false)))
        }
    }

    func readSystemExercises(uid: String, afterId: String?, exerciseId: String?, callback: @escaping (NativeClientOutcome) -> Void) -> String {
        let token = UUID().uuidString
        #if DEBUG
        lock.lock(); defer { lock.unlock() }
        guard productMemoryCache, !terminated, !accountFrozen, auth.currentUser?.uid == uid, generation.projectID == "demo-tillfailure-m3",
              SystemExerciseProtocol.shared.validRequest(uid: uid, afterId: afterId, exerciseId: exerciseId) else {
            callback(NativeClientOutcome(json: nil, error: "UNAVAILABLE")); return token
        }
        let issuedEpoch = invitationAuthEpoch, callbackEpoch = currentEpoch
        var cancelled = false
        cancellations[token] = { cancelled = true }
        func allowed() -> Bool { !cancelled && !self.terminated && !self.accountFrozen && self.auth.currentUser?.uid == uid &&
            self.invitationAuthEpoch == issuedEpoch && self.currentEpoch == callbackEpoch }
        func finish(_ documents: [DocumentSnapshot]?, _ error: Error?) {
            DispatchQueue.main.async {
                self.lock.lock(); defer { self.lock.unlock() }
                guard allowed() else { return }
                self.cancellations.removeValue(forKey: token)
                if let error { callback(NativeClientOutcome(json: nil, error: Self.mapFailure(error).code)); return }
                guard let documents, !documents.contains(where: { $0.metadata.isFromCache || $0.metadata.hasPendingWrites }) else {
                    callback(NativeClientOutcome(json: nil, error: "UNAVAILABLE")); return
                }
                let records = documents.filter { $0.exists }.map { ["id": $0.documentID, "data": $0.data() ?? [:]] as [String: Any] }
                guard let data = try? JSONSerialization.data(withJSONObject: ["documents": records]), data.count <= 256 * 1024,
                      let json = String(data: data, encoding: .utf8) else {
                    callback(NativeClientOutcome(json: nil, error: "MALFORMED")); return
                }
                callback(NativeClientOutcome(json: json, error: nil))
            }
        }
        auth.currentUser!.getIDTokenForcingRefresh(true) { _, error in
            DispatchQueue.main.async {
                self.lock.lock(); defer { self.lock.unlock() }
                guard allowed() else { return }
                if error != nil { self.cancellations.removeValue(forKey: token); callback(NativeClientOutcome(json: nil, error: "UNAUTHENTICATED")); return }
                if let exerciseId {
                    self.firestore.collection("systemExercises").document(exerciseId).getDocument(source: .server) { document, error in finish(document.map { [$0] }, error) }
                } else {
                    var query = self.firestore.collection("systemExercises").whereField("status", isEqualTo: "published").order(by: FieldPath.documentID()).limit(to: 21)
                    if let afterId { query = query.start(after: [afterId]) }
                    query.getDocuments(source: .server) { snapshot, error in finish(snapshot?.documents, error) }
                }
            }
        }
        #else
        callback(NativeClientOutcome(json: nil, error: "UNAVAILABLE"))
        #endif
        return token
    }

    func callProgramDrafts(uid: String, json: String, callback: @escaping (NativeClientOutcome) -> Void) -> String {
        let token = UUID().uuidString
        #if DEBUG
        lock.lock()
        defer { lock.unlock() }
        guard !terminated, !accountFrozen, auth.currentUser?.uid == uid, generation.projectID == "demo-tillfailure-m3",
              json.utf8.count <= 32768, let bytes = json.data(using: .utf8),
              let payload = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              ["draft-prepare", "draft-list", "draft-get"].contains(payload["action"] as? String ?? "") else {
            callback(NativeClientOutcome(json: nil, error: "unavailable")); return token
        }
        let issuedEpoch = invitationAuthEpoch, callbackEpoch = currentEpoch
        var cancelled = false
        cancellations[token] = { cancelled = true }
        func allowed() -> Bool { !cancelled && !self.terminated && !self.accountFrozen && self.auth.currentUser?.uid == uid &&
            self.invitationAuthEpoch == issuedEpoch && self.currentEpoch == callbackEpoch }
        auth.currentUser!.getIDTokenForcingRefresh(true) { [weak self] _, error in
            DispatchQueue.main.async {
                guard let self else { return }
                self.lock.lock(); defer { self.lock.unlock() }
                guard allowed() else { return }
                if error != nil { self.cancellations.removeValue(forKey: token); callback(NativeClientOutcome(json: nil, error: "unavailable")); return }
                let functions = Functions.functions(app: self.generation.app, region: "europe-west1")
                let port = Int(ProcessInfo.processInfo.environment["TF_FUNCTIONS_EMULATOR_PORT"] ?? "") ?? 5001
                functions.useEmulator(withHost: self.emulatorHost, port: port)
                functions.httpsCallable("programDraftsEmulator").call(payload) { result, error in
                    DispatchQueue.main.async {
                        self.lock.lock(); defer { self.lock.unlock() }
                        guard allowed() else { return }
                        self.cancellations.removeValue(forKey: token)
                        if let error = error as NSError? {
                            let denied = error.domain == FunctionsErrorDomain && [FunctionsErrorCode.permissionDenied.rawValue, FunctionsErrorCode.unauthenticated.rawValue].contains(error.code)
                            let reason = denied ? "unauthorized" : "unavailable"
                            callback(NativeClientOutcome(json: nil, error: reason))
                        } else if let data = result?.data, let bytes = try? JSONSerialization.data(withJSONObject: data),
                                  bytes.count <= 64 * 1024, let json = String(data: bytes, encoding: .utf8) {
                            callback(NativeClientOutcome(json: json, error: nil))
                        } else { callback(NativeClientOutcome(json: nil, error: "unavailable")) }
                    }
                }
            }
        }
        #else
        callback(NativeClientOutcome(json: nil, error: "unavailable"))
        #endif
        return token
    }
    func callCustomExercises(uid: String, json: String, callback: @escaping (NativeClientOutcome) -> Void) -> String {
        let token = UUID().uuidString
        #if DEBUG
        lock.lock()
        defer { lock.unlock() }
        guard !terminated, !accountFrozen, auth.currentUser?.uid == uid, generation.projectID == "demo-tillfailure-m3",
              json.utf8.count <= 32768, let bytes = json.data(using: .utf8),
              let payload = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              ["exercise-prepare", "exercise-list", "exercise-get"].contains(payload["action"] as? String ?? "") else {
            callback(NativeClientOutcome(json: nil, error: "unavailable")); return token
        }
        let issuedEpoch = invitationAuthEpoch, callbackEpoch = currentEpoch
        var cancelled = false
        cancellations[token] = { cancelled = true }
        func allowed() -> Bool { !cancelled && !self.terminated && !self.accountFrozen && self.auth.currentUser?.uid == uid &&
            self.invitationAuthEpoch == issuedEpoch && self.currentEpoch == callbackEpoch }
        auth.currentUser!.getIDTokenForcingRefresh(true) { [weak self] _, error in
            DispatchQueue.main.async {
                guard let self else { return }
                self.lock.lock(); defer { self.lock.unlock() }
                guard allowed() else { return }
                if error != nil { self.cancellations.removeValue(forKey: token); callback(NativeClientOutcome(json: nil, error: "unavailable")); return }
                let functions = Functions.functions(app: self.generation.app, region: "europe-west1")
                let port = Int(ProcessInfo.processInfo.environment["TF_FUNCTIONS_EMULATOR_PORT"] ?? "") ?? 5001
                functions.useEmulator(withHost: self.emulatorHost, port: port)
                functions.httpsCallable("customExercisesEmulator").call(payload) { result, error in
                    DispatchQueue.main.async {
                        self.lock.lock(); defer { self.lock.unlock() }
                        guard allowed() else { return }
                        self.cancellations.removeValue(forKey: token)
                        if let error = error as NSError? {
                            let reason = error.domain == FunctionsErrorDomain ? error.localizedDescription : "unavailable"
                            callback(NativeClientOutcome(json: nil, error: reason))
                        } else if let data = result?.data, let bytes = try? JSONSerialization.data(withJSONObject: data),
                                  bytes.count <= 64 * 1024, let json = String(data: bytes, encoding: .utf8) {
                            callback(NativeClientOutcome(json: json, error: nil))
                        } else { callback(NativeClientOutcome(json: nil, error: "unavailable")) }
                    }
                }
            }
        }
        #else
        callback(NativeClientOutcome(json: nil, error: "unavailable"))
        #endif
        return token
    }
    func callClients(uid: String, json: String, callback: @escaping (NativeClientOutcome) -> Void) -> String {
        let token = UUID().uuidString
        #if DEBUG
        lock.lock()
        defer { lock.unlock() }
        guard !terminated, !accountFrozen, auth.currentUser?.uid == uid, generation.projectID == "demo-tillfailure-m3",
              json.utf8.count <= 8192, let bytes = json.data(using: .utf8),
              let payload = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              ["list", "get"].contains(payload["action"] as? String ?? "") else {
            callback(NativeClientOutcome(json: nil, error: "unavailable")); return token
        }
        let issuedEpoch = invitationAuthEpoch, callbackEpoch = currentEpoch
        var cancelled = false
        cancellations[token] = { cancelled = true }
        func allowed() -> Bool { !cancelled && !self.terminated && !self.accountFrozen && self.auth.currentUser?.uid == uid &&
            self.invitationAuthEpoch == issuedEpoch && self.currentEpoch == callbackEpoch }
        auth.currentUser!.getIDTokenForcingRefresh(true) { [weak self] _, error in
            DispatchQueue.main.async {
                guard let self else { return }
                self.lock.lock(); defer { self.lock.unlock() }
                guard allowed() else { return }
                if error != nil { self.cancellations.removeValue(forKey: token); callback(NativeClientOutcome(json: nil, error: "unavailable")); return }
                let functions = Functions.functions(app: self.generation.app, region: "europe-west1")
                let port = Int(ProcessInfo.processInfo.environment["TF_FUNCTIONS_EMULATOR_PORT"] ?? "") ?? 5001
                functions.useEmulator(withHost: self.emulatorHost, port: port)
                functions.httpsCallable("clientsEmulator").call(payload) { result, error in
                    DispatchQueue.main.async {
                        self.lock.lock(); defer { self.lock.unlock() }
                        guard allowed() else { return }
                        self.cancellations.removeValue(forKey: token)
                        if let error = error as NSError? {
                            let reason = error.domain == FunctionsErrorDomain ? error.localizedDescription : "unavailable"
                            callback(NativeClientOutcome(json: nil, error: reason))
                        } else if let data = result?.data, let bytes = try? JSONSerialization.data(withJSONObject: data),
                                  bytes.count <= 64 * 1024, let json = String(data: bytes, encoding: .utf8) {
                            callback(NativeClientOutcome(json: json, error: nil))
                        } else { callback(NativeClientOutcome(json: nil, error: "unavailable")) }
                    }
                }
            }
        }
        #else
        callback(NativeClientOutcome(json: nil, error: "unavailable"))
        #endif
        return token
    }

    #if DEBUG
    var debugBeforeCommandRetirement: (() -> Void)?
    #endif
    func noteBindingNonce() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { return "" }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
    func beforeCommandRetirement() {
        #if DEBUG
        debugBeforeCommandRetirement?()
        #endif
    }
    func callInvitation(uid: String, json: String, prepare: @escaping () -> KotlinBoolean,
                        callback: @escaping (NativeInvitationOutcome) -> Void) -> String {
        let token = UUID().uuidString
        lock.lock()
        defer { lock.unlock() }
        guard !terminated, !accountFrozen, !invitationInFlight, auth.currentUser?.uid == uid, generation.projectID == "demo-tillfailure-m3",
              let data = json.data(using: .utf8), let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              prepare().boolValue else {
            callback(NativeInvitationOutcome(json: nil, error: "unavailable", ambiguous: true))
            return token
        }
        let functions = Functions.functions(app: generation.app, region: "europe-west1")
        #if DEBUG
        let port = Int(ProcessInfo.processInfo.environment["TF_FUNCTIONS_EMULATOR_PORT"] ?? "") ?? 5001
        #else
        let port = 5001
        #endif
        functions.useEmulator(withHost: emulatorHost, port: port)
        let issuedEpoch = invitationAuthEpoch
        let callbackEpoch = currentEpoch
        invitationInFlight = true
        let transport = {
          self.lock.lock(); defer { self.lock.unlock() }
          guard !self.terminated, self.auth.currentUser?.uid == uid,
                self.invitationAuthEpoch == issuedEpoch, self.currentEpoch == callbackEpoch else { return }
          let action = payload["action"] as? String ?? ""
          let endpoint = payload["protocolVersion"] != nil ? "commandResolutionEmulator" : action == "save-program-draft" ? "programDraftsEmulator" : ["create-custom-exercise", "correct-custom-exercise"].contains(action) ? "customExercisesEmulator" : ["note-get", "save-trainer-note"].contains(action) ? "trainerNotesEmulator" : ["prepare-client-revocation", "revoke-client"].contains(action) ? "clientRevocationEmulator" : action == "complete-onboarding" ? "onboardingEmulator" :
              (["profile-get", "update-profile"].contains(action) ? "basicProfileEmulator" : "invitationEmulator")
          let send = {
          functions.httpsCallable(endpoint).call(payload) { [weak self] result, error in
            DispatchQueue.main.async {
                guard let self else { return }
                let deliver = {
                self.lock.lock()
                defer { self.lock.unlock() }
                self.invitationInFlight = false
                guard !self.terminated, self.auth.currentUser?.uid == uid,
                      self.invitationAuthEpoch == issuedEpoch, self.currentEpoch == callbackEpoch else { return }
                // Resolve the protected command even when its initiating view has gone away.
                if let error = error as NSError? {
                    let definitive = error.domain == FunctionsErrorDomain && [FunctionsErrorCode.invalidArgument.rawValue,
                        FunctionsErrorCode.unauthenticated.rawValue, FunctionsErrorCode.permissionDenied.rawValue,
                        FunctionsErrorCode.failedPrecondition.rawValue, FunctionsErrorCode.resourceExhausted.rawValue].contains(error.code)
                    callback(NativeInvitationOutcome(json: nil, error: error.localizedDescription, ambiguous: !definitive))
                } else if let payload = result?.data, let bytes = try? JSONSerialization.data(withJSONObject: payload),
                          let json = String(data: bytes, encoding: .utf8) {
                    callback(NativeInvitationOutcome(json: json, error: nil, ambiguous: false))
                } else {
                    callback(NativeInvitationOutcome(json: nil, error: "unavailable", ambiguous: true))
                }
                }
                #if DEBUG
                if let hold = self.debugBeforeInvitationCompletion { hold(deliver) } else { deliver() }
                #else
                deliver()
                #endif
            }
          }
          }
          if ["save-program-draft", "create-custom-exercise", "correct-custom-exercise"].contains(action) {
            self.auth.currentUser!.getIDTokenForcingRefresh(true) { _, error in
              DispatchQueue.main.async {
                self.lock.lock(); defer { self.lock.unlock() }
                guard !self.terminated, self.auth.currentUser?.uid == uid, self.invitationAuthEpoch == issuedEpoch, self.currentEpoch == callbackEpoch else { return }
                if error != nil { self.invitationInFlight = false; callback(NativeInvitationOutcome(json: nil, error: "unavailable", ambiguous: true)) }
                else { send() }
              }
            }
          } else { send() }
        }
        #if DEBUG
        if let hold = debugBeforeInvitationTransport { hold(transport) } else { transport() }
        #else
        transport()
        #endif
        return token
    }

    func discoverMemberships(uid: String, accountEpoch: Int64, callback: @escaping (NativeMembershipDiscoveryResult) -> Void) -> String {
        activate(epoch: accountEpoch)
        if let token = issueIfLive({
            let (token, gate) = beginOneShot()
            firestore.collection("users/\(uid)/membershipRefs")
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
        let data = snapshot.data() ?? [:]
        var fields = data.mapValues { String(describing: $0) }
        let identityPath = snapshot.reference.path.range(of: "^(users/[^/]+|workspaces/[^/]+|workspaces/[^/]+/memberships/[^/]+)$", options: .regularExpression) != nil
        for (key, value) in data {
            if identityPath && ["schemaVersion", "lifecycleRevision", "revision", "membershipRevision", "activeRosterCount", "catalogContributionCount"].contains(key) {
                if let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
                   ["q", "i", "s", "l"].contains(String(cString: number.objCType)) {} else { fields["_invalidIdentityTypes"] = "true" }
            }
            if identityPath && key == "catalogContributionActive" {
                if let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() {
                    fields[key] = number.boolValue ? "true" : "false"
                } else { fields["_invalidIdentityTypes"] = "true" }
            }
            if ["displayName", "locale", "timeZoneId"].contains(key) && !(value is String) {
                fields["_invalidOnboardingTypes"] = "true"
            }
            if ["profileRevision", "onboardingRevision", "onboardingPolicyVersion"].contains(key) {
                if let number = value as? NSNumber {
                    if CFGetTypeID(number) == CFBooleanGetTypeID() || !["q", "i", "s", "l"].contains(String(cString: number.objCType)) {
                        fields["_invalidOnboardingTypes"] = "true"
                    }
                } else { fields["_invalidOnboardingTypes"] = "true" }
            }
        }
        return NativeFirebaseDocument(
            path: snapshot.reference.path,
            fields: fields,
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
