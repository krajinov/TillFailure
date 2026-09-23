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

            // Commit atomicity: a failure while configuring the required file metadata must abort
            // before the replacement, so the previous committed record stays byte-identical, the new
            // payload is never committed, and the temporary file is removed.
            let committedRaw = try bridge.debugRawData(uid: firstUid)
            let uncommitted = "{\"uid\":\"\(firstUid)\",\"payload\":\"uncommitted-replacement\"}"
            bridge.debugFailMetadataSetup = true
            let failedWrite = bridge.write(uid: firstUid, plaintext: uncommitted)
            bridge.debugFailMetadataSetup = false
            record("commitMetadataFailureReported", failedWrite.failureCode == "LOCKED")
            let preservedRaw = try bridge.debugRawData(uid: firstUid)
            record("commitMetadataFailurePreservesCommittedBytes", preservedRaw == committedRaw)
            record("commitMetadataFailurePreservesPayload", bridge.read(uid: firstUid).payload == plaintext)
            record("commitMetadataFailureNotCommitted", bridge.read(uid: firstUid).payload != uncommitted)
            record("commitMetadataFailureCleansTemporaryFiles", bridge.debugTemporaryFileCount() == 0)

            // The same partition still commits normally afterwards and survives a restart.
            let recovered = "{\"uid\":\"\(firstUid)\",\"payload\":\"replacement-after-metadata-failure\"}"
            record("commitAfterMetadataFailure", bridge.write(uid: firstUid, plaintext: recovered).failureCode == nil && bridge.read(uid: firstUid).payload == recovered)
            let restartedAfterFailure = RecoveryPersistenceBridge(rootURL: root, keyIdentifier: keyIdentifier)
            record("commitAfterMetadataFailureRestart", restartedAfterFailure.read(uid: firstUid).payload == recovered)
            record("commitMetadataRetainedBackupExclusion", bridge.debugIsExcludedFromBackup(uid: firstUid))
            record("commitMetadataRetainedProtection", bridge.debugProtectionConfigurationAccepted(uid: firstUid))

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
            // Flip the first base64 character to a *different* one: writing a fixed replacement can be a
            // no-op when the ciphertext already starts with that character, which would leave the record
            // readable and make this assertion flaky.
            let flipped = ciphertext.hasPrefix("A") ? "B" : "A"
            tamperedObject["ciphertext"] = ciphertext.isEmpty ? flipped : flipped + ciphertext.dropFirst()
            let tampered = try JSONSerialization.data(withJSONObject: tamperedObject, options: [.sortedKeys])
            record("tamperInjectionChangedBytes", tampered != secondRaw)
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
