import Foundation

#if DEBUG
enum RecoveryPersistenceSpikeHarness {
    static func run() -> Bool {
        let suffix = UUID().uuidString
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TillFailureRecoverySpike-\(suffix)", isDirectory: true)
        let keyIdentifier = "tillfailure.recovery.v1.test.\(suffix)"
        let firstUid = "uid_one_\(suffix)"
        let secondUid = "uid_two_\(suffix)"
        let plaintext = "{\"uid\":\"\(firstUid)\",\"payload\":\"plaintext-recovery-fragment\"}"
        let secondPlaintext = "{\"uid\":\"\(secondUid)\",\"payload\":\"second-account\"}"
        let bridge = RecoveryPersistenceBridge(rootURL: root, keyIdentifier: keyIdentifier)
        var passed = true

        func record(_ name: String, _ condition: @autoclosure () -> Bool) {
            let result = condition()
            passed = passed && result
            print("M3_RECOVERY \(name)=\(result ? "PASS" : "FAIL")")
        }

        defer {
            bridge.debugCleanup(uid: firstUid)
            bridge.debugCleanup(uid: secondUid)
            bridge.debugDeleteKey()
            try? FileManager.default.removeItem(at: root)
        }

        do {
            record("write", bridge.write(uid: firstUid, plaintext: plaintext).failureCode == nil)
            let firstRaw = try bridge.debugRawData(uid: firstUid)
            let firstObject = try jsonObject(firstRaw)
            let firstNonce = firstObject["nonce"] as? String
            record("version", firstObject["encryptionVersion"] as? Int == 1 && firstObject["keyIdentifier"] as? String == keyIdentifier)
            record("plaintextAbsent", !String(decoding: firstRaw, as: UTF8.self).contains("plaintext-recovery-fragment"))
            record("backupExcluded", bridge.debugIsExcludedFromBackup(uid: firstUid))
            let protectionClass = bridge.debugProtectionClass(uid: firstUid)
            print("M3_RECOVERY dataProtectionObserved=\(protectionClass ?? "unavailable")")
            record("dataProtectionConfiguration", bridge.debugProtectionConfigurationAccepted(uid: firstUid))
            record("keychainPolicy", bridge.debugKeyUsesDeviceOnlyAfterFirstUnlock())

            record("replacement", bridge.write(uid: firstUid, plaintext: plaintext).failureCode == nil)
            let secondRaw = try bridge.debugRawData(uid: firstUid)
            let secondNonce = try jsonObject(secondRaw)["nonce"] as? String
            record("uniqueNonce", firstNonce != nil && firstNonce != secondNonce)
            record("atomicRead", bridge.read(uid: firstUid).payload == plaintext)
            record("temporaryCleanup", bridge.debugTemporaryFileCount() == 0)

            record("secondPartitionWrite", bridge.write(uid: secondUid, plaintext: secondPlaintext).failureCode == nil)
            let restarted = RecoveryPersistenceBridge(rootURL: root, keyIdentifier: keyIdentifier)
            record("restartRecovery", restarted.read(uid: firstUid).payload == plaintext)
            record("uidIsolation", restarted.read(uid: secondUid).payload == secondPlaintext)
            let wrongKey = RecoveryPersistenceBridge(rootURL: root, keyIdentifier: "\(keyIdentifier).wrong")
            record("wrongKeyFailsClosed", wrongKey.read(uid: firstUid).failureCode == "LOCKED")

            var tamperedObject = try jsonObject(secondRaw)
            let ciphertext = tamperedObject["ciphertext"] as? String ?? ""
            tamperedObject["ciphertext"] = ciphertext.isEmpty ? "A" : "A" + ciphertext.dropFirst()
            let tampered = try JSONSerialization.data(withJSONObject: tamperedObject, options: [.sortedKeys])
            try bridge.debugOverwrite(uid: firstUid, data: tampered)
            record("tamperFailsClosed", bridge.read(uid: firstUid).failureCode == "LOCKED")
            record("lockedDeletePreserves", bridge.delete(uid_: firstUid).failureCode == "LOCKED" && bridge.debugFileExists(uid: firstUid))
            record("lockedReplacementPreserves", bridge.write(uid: firstUid, plaintext: plaintext).failureCode == "LOCKED" && bridge.debugFileExists(uid: firstUid))

            try bridge.debugOverwrite(uid: firstUid, data: secondRaw)
            bridge.debugDeleteKey()
            record("missingKeyFailsClosed", bridge.read(uid: firstUid).failureCode == "LOCKED")
            record("keyLossIsolatesAllPartitions", bridge.read(uid: secondUid).failureCode == "LOCKED")
            record("keyLossPreservesFiles", bridge.debugFileExists(uid: firstUid) && bridge.debugFileExists(uid: secondUid))
        } catch {
            passed = false
            print("M3_RECOVERY unexpectedFailure=FAIL code=\((error as NSError).code)")
        }

        print("M3_RECOVERY complete=\(passed ? "PASS" : "FAIL")")
        return passed
    }

    private static func jsonObject(_ data: Data) throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw NSError(domain: "TillFailureRecoverySpike", code: 1)
        }
        return object
    }
}
#endif
