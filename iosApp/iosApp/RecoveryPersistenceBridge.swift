import CryptoKit
import Darwin
import Foundation
import Security
import Shared

final class RecoveryPersistenceBridge: NSObject, NativeRecoveryPersistenceBridge {
    private struct EncryptedEnvelope: Codable {
        let encryptionVersion: Int
        let keyIdentifier: String
        let nonce: String
        let ciphertext: String
        let tag: String
    }

    let keyIdentifier: String
    let encryptionVersion: Int32 = 1

    private let rootURL: URL
    private let fileManager = FileManager.default

    init(
        rootURL: URL? = nil,
        keyIdentifier: String = "tillfailure.recovery.v1"
    ) {
        self.rootURL = rootURL ?? FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TillFailureRecovery", isDirectory: true)
        self.keyIdentifier = keyIdentifier
        super.init()
    }

    func write(uid: String, plaintext: String) -> NativeRecoveryPersistenceResult {
        do {
            try validate(uid: uid)
            try prepareRoot()
            let target = fileURL(for: uid)
            if fileManager.fileExists(atPath: target.path) {
                _ = try readPlaintext(uid: uid)
            }
            let key = try loadKey(createIfMissing: true)
            let nonce = AES.GCM.Nonce()
            let sealed = try AES.GCM.seal(
                Data(plaintext.utf8),
                using: key,
                nonce: nonce,
                authenticating: associatedData(uid: uid)
            )
            let envelope = EncryptedEnvelope(
                encryptionVersion: Int(encryptionVersion),
                keyIdentifier: keyIdentifier,
                nonce: Data(sealed.nonce).base64EncodedString(),
                ciphertext: sealed.ciphertext.base64EncodedString(),
                tag: sealed.tag.base64EncodedString()
            )
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            try atomicallyReplace(target: target, data: encoder.encode(envelope))
            return success()
        } catch {
            return locked()
        }
    }

    func read(uid: String) -> NativeRecoveryPersistenceResult {
        do {
            try validate(uid: uid)
            let target = fileURL(for: uid)
            guard fileManager.fileExists(atPath: target.path) else { return success(payload: nil) }
            return success(payload: try readPlaintext(uid: uid))
        } catch {
            return locked()
        }
    }

    func delete(uid_ uid: String) -> NativeRecoveryPersistenceResult {
        do {
            try validate(uid: uid)
            let target = fileURL(for: uid)
            guard fileManager.fileExists(atPath: target.path) else { return success() }
            _ = try readPlaintext(uid: uid)
            try fileManager.removeItem(at: target)
            return success()
        } catch {
            return locked()
        }
    }

    private func readPlaintext(uid: String) throws -> String {
        let data = try Data(contentsOf: fileURL(for: uid))
        let envelope = try JSONDecoder().decode(EncryptedEnvelope.self, from: data)
        guard envelope.encryptionVersion == Int(encryptionVersion), envelope.keyIdentifier == keyIdentifier else {
            throw PersistenceError.locked
        }
        guard
            let nonceData = Data(base64Encoded: envelope.nonce),
            let ciphertext = Data(base64Encoded: envelope.ciphertext),
            let tag = Data(base64Encoded: envelope.tag)
        else { throw PersistenceError.locked }
        let sealed = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonceData),
            ciphertext: ciphertext,
            tag: tag
        )
        let plaintext = try AES.GCM.open(
            sealed,
            using: loadKey(createIfMissing: false),
            authenticating: associatedData(uid: uid)
        )
        guard let value = String(data: plaintext, encoding: .utf8) else { throw PersistenceError.locked }
        return value
    }

    private func atomicallyReplace(target: URL, data: Data) throws {
        let temporary = target.appendingPathExtension("tmp")
        if fileManager.fileExists(atPath: temporary.path) {
            try fileManager.removeItem(at: temporary)
        }
        do {
            try data.write(to: temporary, options: .completeFileProtectionUntilFirstUserAuthentication)
            let handle = try FileHandle(forWritingTo: temporary)
            defer { try? handle.close() }
            try handle.synchronize()
            // Every required file attribute is applied to the temporary file before the rename, so a
            // metadata failure aborts the write while the previously committed record is untouched.
            try applyCommitMetadata(to: temporary)
        } catch {
            try? fileManager.removeItem(at: temporary)
            throw error
        }
        // The rename below is the commit point. No fallible work runs after it, so a write either
        // reports failure with the previous record intact or success with the new record committed.
        guard rename(temporary.path, target.path) == 0 else {
            try? fileManager.removeItem(at: temporary)
            throw PersistenceError.atomicReplacementFailed
        }
    }

    // Required commit metadata for a recovery partition: `completeUntilFirstUserAuthentication`
    // protection and backup exclusion. Both are configured on the temporary file so that the
    // atomic replacement is the only step that can make the new payload visible.
    private func applyCommitMetadata(to url: URL) throws {
        #if DEBUG
        if debugFailMetadataSetup { throw PersistenceError.atomicReplacementFailed }
        #endif
        try fileManager.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: url.path
        )
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableURL = url
        try mutableURL.setResourceValues(values)
    }

    private func prepareRoot() throws {
        try fileManager.createDirectory(
            at: rootURL,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableRoot = rootURL
        try mutableRoot.setResourceValues(values)
    }

    private func loadKey(createIfMissing: Bool) throws -> SymmetricKey {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keyIdentifier,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecSuccess, let data = item as? Data {
            return SymmetricKey(data: data)
        }
        guard status == errSecItemNotFound, createIfMissing else { throw PersistenceError.keyUnavailable }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw PersistenceError.keyUnavailable
        }
        let data = Data(bytes)
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keyIdentifier,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: data
        ]
        guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else { throw PersistenceError.keyUnavailable }
        return SymmetricKey(data: data)
    }

    private func associatedData(uid: String) -> Data {
        Data("TillFailureRecovery|\(encryptionVersion)|\(keyIdentifier)|\(uid)".utf8)
    }

    private func fileURL(for uid: String) -> URL {
        let digest = SHA256.hash(data: Data(uid.utf8)).map { String(format: "%02x", $0) }.joined()
        return rootURL.appendingPathComponent("account-\(digest)-v\(encryptionVersion).json")
    }

    private func validate(uid: String) throws {
        // Shared cross-platform contract: the Firebase Auth UID bound (non-blank, at most 128
        // UTF-16 code units) with no filename-safe character whitelist, because only the SHA-256
        // digest is ever used as the partition filename.
        guard RecoveryUidContract.shared.isValid(uid: uid) else {
            throw PersistenceError.invalidUid
        }
    }

    private var keychainService: String { "com.delminiusapps.tillfailure.recovery" }

    private func success(payload: String? = nil) -> NativeRecoveryPersistenceResult {
        NativeRecoveryPersistenceResult(payload: payload, failureCode: nil)
    }

    private func locked() -> NativeRecoveryPersistenceResult {
        NativeRecoveryPersistenceResult(payload: nil, failureCode: "LOCKED")
    }

    private enum PersistenceError: Error {
        case invalidUid
        case keyUnavailable
        case locked
        case atomicReplacementFailed
    }

    #if DEBUG
    /// Test-only injection: when set, configuring the required commit metadata for the temporary
    /// file throws, so the harness can prove that a pre-commit failure preserves the previously
    /// committed record, never commits the new payload, and removes the temporary file.
    var debugFailMetadataSetup = false

    func debugRawData(uid: String) throws -> Data { try Data(contentsOf: fileURL(for: uid)) }

    func debugPartitionFileName(uid: String) -> String { fileURL(for: uid).lastPathComponent }

    func debugOverwrite(uid: String, data: Data) throws { try data.write(to: fileURL(for: uid)) }

    func debugFileExists(uid: String) -> Bool { fileManager.fileExists(atPath: fileURL(for: uid).path) }

    func debugTemporaryFileCount() -> Int {
        (try? fileManager.contentsOfDirectory(at: rootURL, includingPropertiesForKeys: nil))?
            .filter { $0.pathExtension == "tmp" }.count ?? 0
    }

    func debugIsExcludedFromBackup(uid: String) -> Bool {
        (try? fileURL(for: uid).resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup) == true
    }

    func debugProtectionClass(uid: String) -> String? {
        let attributes = try? fileManager.attributesOfItem(atPath: fileURL(for: uid).path)
        if let protection = attributes?[.protectionKey] as? FileProtectionType {
            return protection.rawValue
        }
        return attributes?[.protectionKey] as? String
    }

    func debugProtectionConfigurationAccepted(uid: String) -> Bool {
        do {
            try fileManager.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: fileURL(for: uid).path
            )
            return true
        } catch {
            return false
        }
    }

    func debugKeyUsesDeviceOnlyAfterFirstUnlock() -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keyIdentifier,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let attributes = item as? [String: Any]
        else { return false }
        return (attributes[kSecAttrAccessible as String] as? String) == (kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
    }

    func debugDeleteKey() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keyIdentifier
        ]
        SecItemDelete(query as CFDictionary)
    }

    func debugCleanup(uid: String) {
        try? fileManager.removeItem(at: fileURL(for: uid))
    }
    #endif
}
