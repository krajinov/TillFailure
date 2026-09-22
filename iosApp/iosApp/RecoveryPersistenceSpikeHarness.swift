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
        // Valid Firebase UIDs outside a filename-safe whitelist: only the digest names the file.
        let emailUid = "user@example.com+\(suffix)"
        let distinctUid = "user+alias@example.com+\(suffix)"
        let punctuationUid = "a.b-c_d:e|f!g-\(suffix)"
        let unicodeUid = "üser-Ω-\(suffix)"
        let maxLengthUid = String(repeating: "u", count: 128)
        let traversalUid = "../../etc/passwd-\(suffix)"
        let overlengthUid = String(repeating: "u", count: 129)
        let domainUids = [emailUid, distinctUid, punctuationUid, unicodeUid, maxLengthUid, traversalUid]
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
            domainUids.forEach { bridge.debugCleanup(uid: $0) }
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

            // Complete Firebase UID domain: email-shaped, punctuated, Unicode, maximum-length, and
            // traversal-like identifiers all round-trip because only the digest names the file.
            record("emailUidRoundTrip", bridge.write(uid: emailUid, plaintext: "email-account").failureCode == nil && bridge.read(uid: emailUid).payload == "email-account")
            record("punctuationUidRoundTrip", bridge.write(uid: punctuationUid, plaintext: "punctuation-account").failureCode == nil && bridge.read(uid: punctuationUid).payload == "punctuation-account")
            record("unicodeUidRoundTrip", bridge.write(uid: unicodeUid, plaintext: "unicode-account").failureCode == nil && bridge.read(uid: unicodeUid).payload == "unicode-account")
            record("maxLengthUidRoundTrip", bridge.write(uid: maxLengthUid, plaintext: "max-length-account").failureCode == nil && bridge.read(uid: maxLengthUid).payload == "max-length-account")
            record("traversalUidRoundTrip", bridge.write(uid: traversalUid, plaintext: "traversal-account").failureCode == nil && bridge.read(uid: traversalUid).payload == "traversal-account")
            let traversalName = bridge.debugPartitionFileName(uid: traversalUid)
            record("partitionNameIsDigestOnly", traversalName.range(of: "^account-[0-9a-f]{64}-v1\\.json$", options: .regularExpression) != nil)
            record("distinctUidsDistinctPartitions", bridge.debugPartitionFileName(uid: emailUid) != bridge.debugPartitionFileName(uid: distinctUid))
            record("crossUidIsolation", bridge.read(uid: distinctUid).payload == nil)
            record("emptyUidRejected", bridge.read(uid: "").failureCode == "LOCKED" && bridge.write(uid: "", plaintext: "empty").failureCode == "LOCKED")
            record("blankUidRejected", bridge.read(uid: "   ").failureCode == "LOCKED")
            record("overlengthUidRejected", bridge.read(uid: overlengthUid).failureCode == "LOCKED" && bridge.write(uid: overlengthUid, plaintext: "overlength").failureCode == "LOCKED")
            try bridge.debugOverwrite(uid: punctuationUid, data: bridge.debugRawData(uid: emailUid))
            record("wrongUidCannotDecrypt", bridge.read(uid: punctuationUid).failureCode == "LOCKED")
            bridge.debugCleanup(uid: punctuationUid)

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
