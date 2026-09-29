import Foundation
import Shared

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

            runConcurrentChecks(root: root, keyIdentifier: keyIdentifier, suffix: suffix) { name, result in
                record(name, result)
            }
            runFirstKeyCreationRace(root: root, keyIdentifier: keyIdentifier, suffix: suffix) { name, result in
                record(name, result)
            }

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

    private static func runConcurrentChecks(
        root: URL,
        keyIdentifier: String,
        suffix: String,
        record: (String, Bool) -> Void
    ) {
        let uid = "overlap_\(suffix)"
        let otherUid = "overlap_other_\(suffix)"
        let thirdUid = "overlap_third_\(suffix)"
        let first = RecoveryPersistenceBridge(rootURL: root, keyIdentifier: keyIdentifier)
        let second = RecoveryPersistenceBridge(rootURL: root, keyIdentifier: keyIdentifier)
        let restarted = RecoveryPersistenceBridge(rootURL: root, keyIdentifier: keyIdentifier)
        let queue = DispatchQueue(label: "recovery-overlap", attributes: .concurrent)
        func reached(_ semaphore: DispatchSemaphore) -> Bool {
            semaphore.wait(timeout: .now() + 20) == .success
        }
        final class ResultBox {
            var result: NativeRecoveryPersistenceResult?
        }
        defer {
            [uid, otherUid, thirdUid].forEach { first.debugCleanup(uid: $0) }
            first.debugBeforeCommit = nil
            first.debugBeforeDelete = nil
            second.debugBeforeCommit = nil
            second.debugBeforePartitionLock = nil
        }

        record("overlapInitialWrite", first.write(uid: uid, plaintext: "initial").failureCode == nil)
        do {
            let initialBytes = try first.debugRawData(uid: uid)
            let aReady = DispatchSemaphore(value: 0)
            let releaseA = DispatchSemaphore(value: 0)
            let bWaiting = DispatchSemaphore(value: 0)
            let aDone = DispatchSemaphore(value: 0)
            let bDone = DispatchSemaphore(value: 0)
            let aResult = ResultBox()
            let bResult = ResultBox()
            first.debugBeforeCommit = { _, _ in aReady.signal(); _ = reached(releaseA) }
            second.debugBeforePartitionLock = { _ in bWaiting.signal() }
            queue.async { aResult.result = first.write(uid: uid, plaintext: "write-A"); aDone.signal() }
            let aEntered = reached(aReady)
            queue.async { bResult.result = second.write(uid: uid, plaintext: "write-B"); bDone.signal() }
            let bEntered = reached(bWaiting)
            let committedBeforeRelease = try first.debugRawData(uid: uid) == initialBytes
            releaseA.signal()
            let bothFinished = reached(aDone) && reached(bDone)
            record("overlapWriteWriteSerialized", aEntered && bEntered && committedBeforeRelease && bothFinished && aResult.result?.failureCode == nil && bResult.result?.failureCode == nil && restarted.read(uid: uid).payload == "write-B")
            first.debugBeforeCommit = nil
            second.debugBeforePartitionLock = nil

            let writeReady = DispatchSemaphore(value: 0)
            let releaseWrite = DispatchSemaphore(value: 0)
            let deleteWaiting = DispatchSemaphore(value: 0)
            let writeDone = DispatchSemaphore(value: 0)
            let deleteDone = DispatchSemaphore(value: 0)
            first.debugBeforeCommit = { _, _ in writeReady.signal(); _ = reached(releaseWrite) }
            second.debugBeforePartitionLock = { _ in deleteWaiting.signal() }
            queue.async { aResult.result = first.write(uid: uid, plaintext: "before-delete"); writeDone.signal() }
            let writeEntered = reached(writeReady)
            queue.async { bResult.result = second.delete(uid_: uid); deleteDone.signal() }
            let deleteEntered = reached(deleteWaiting)
            releaseWrite.signal()
            let writeDeleteFinished = reached(writeDone) && reached(deleteDone)
            record("overlapWriteDeleteSerialized", writeEntered && deleteEntered && writeDeleteFinished && aResult.result?.failureCode == nil && bResult.result?.failureCode == nil && restarted.read(uid: uid).payload == nil)
            first.debugBeforeCommit = nil
            second.debugBeforePartitionLock = nil

            record("overlapDeleteWriteBaseline", first.write(uid: uid, plaintext: "before-delete-write").failureCode == nil)
            let deleteReady = DispatchSemaphore(value: 0)
            let releaseDelete = DispatchSemaphore(value: 0)
            let writeWaiting = DispatchSemaphore(value: 0)
            let firstDeleteDone = DispatchSemaphore(value: 0)
            let secondWriteDone = DispatchSemaphore(value: 0)
            first.debugBeforeDelete = { _ in deleteReady.signal(); _ = reached(releaseDelete) }
            second.debugBeforePartitionLock = { _ in writeWaiting.signal() }
            queue.async { aResult.result = first.delete(uid_: uid); firstDeleteDone.signal() }
            let firstDeleteEntered = reached(deleteReady)
            queue.async { bResult.result = second.write(uid: uid, plaintext: "after-delete"); secondWriteDone.signal() }
            let secondWriteEntered = reached(writeWaiting)
            releaseDelete.signal()
            let deleteWriteFinished = reached(firstDeleteDone) && reached(secondWriteDone)
            record("overlapDeleteWriteSerialized", firstDeleteEntered && secondWriteEntered && deleteWriteFinished && aResult.result?.failureCode == nil && bResult.result?.failureCode == nil && restarted.read(uid: uid).payload == "after-delete")
            first.debugBeforeDelete = nil
            second.debugBeforePartitionLock = nil

            record("overlapOtherUidBaseline", second.write(uid: otherUid, plaintext: "other-initial").failureCode == nil)
            let committed = try first.debugRawData(uid: uid)
            let otherCommitted = try second.debugRawData(uid: otherUid)
            let independentReady = DispatchSemaphore(value: 0)
            let releaseIndependent = DispatchSemaphore(value: 0)
            let independentDone = DispatchSemaphore(value: 0)
            let ownedTemp = ResultBoxURL()
            first.debugBeforeCommit = { _, temporary in
                ownedTemp.url = temporary
                independentReady.signal()
                _ = reached(releaseIndependent)
            }
            second.debugBeforeCommit = { account, _ in
                if account == otherUid { throw NSError(domain: "injected-write-failure", code: 1) }
            }
            queue.async { aResult.result = first.write(uid: uid, plaintext: "after-overlap"); independentDone.signal() }
            let independentEntered = reached(independentReady)
            let thirdWrite = second.write(uid: thirdUid, plaintext: "independent-success")
            let failedOtherWrite = second.write(uid: otherUid, plaintext: "uncommitted")
            let tempPreserved = ownedTemp.url.map { FileManager.default.fileExists(atPath: $0.path) } == true
            let committedPreserved = try first.debugRawData(uid: uid) == committed
            let otherPreserved = try second.debugRawData(uid: otherUid) == otherCommitted
            releaseIndependent.signal()
            let independentFinished = reached(independentDone)
            record("overlapDifferentUidsIndependent", independentEntered && independentFinished && thirdWrite.failureCode == nil && restarted.read(uid: thirdUid).payload == "independent-success")
            record("overlapFailureOwnsTemporary", failedOtherWrite.failureCode == "LOCKED" && tempPreserved && committedPreserved && otherPreserved && restarted.read(uid: uid).payload == "after-overlap" && restarted.read(uid: otherUid).payload == "other-initial")
            first.debugBeforeCommit = nil
            second.debugBeforeCommit = nil

            let lastBytes = try first.debugRawData(uid: uid)
            second.debugBeforeCommit = { _, _ in throw NSError(domain: "injected-write-failure", code: 2) }
            let failedSameUid = second.write(uid: uid, plaintext: "not-committed")
            second.debugBeforeCommit = nil
            record("overlapFailedWritePreservesCommitted", failedSameUid.failureCode == "LOCKED" && (try? first.debugRawData(uid: uid)) == lastBytes && restarted.read(uid: uid).payload == "after-overlap")
            let stale = root.appendingPathComponent("\(first.debugPartitionFileName(uid: uid)).stale.tmp")
            try Data("stale".utf8).write(to: stale)
            record("overlapRestartIgnoresStaleTemporary", restarted.read(uid: uid).payload == "after-overlap" && FileManager.default.fileExists(atPath: stale.path))
            try FileManager.default.removeItem(at: stale)
            record("overlapTemporaryCleanup", first.debugTemporaryFileCount() == 0)
        } catch {
            record("overlapUnexpectedFailure", false)
            print("M3_RECOVERY overlapError=\((error as NSError).code)")
        }
    }

    private final class ResultBoxURL {
        var url: URL?
    }

    private static func runFirstKeyCreationRace(
        root: URL,
        keyIdentifier: String,
        suffix: String,
        record: (String, Bool) -> Void
    ) {
        let raceRoot = root.appendingPathComponent("first-key-race", isDirectory: true)
        let raceKey = "\(keyIdentifier).first-key-race"
        let firstUid = "first_key_a_\(suffix)"
        let secondUid = "first_key_b_\(suffix)"
        let first = RecoveryPersistenceBridge(rootURL: raceRoot, keyIdentifier: raceKey)
        let second = RecoveryPersistenceBridge(rootURL: raceRoot, keyIdentifier: raceKey)
        let ready = DispatchSemaphore(value: 0)
        let release = DispatchSemaphore(value: 0)
        let firstDone = DispatchSemaphore(value: 0)
        let secondDone = DispatchSemaphore(value: 0)
        final class ResultBox { var result: NativeRecoveryPersistenceResult? }
        let firstResult = ResultBox()
        let secondResult = ResultBox()
        let reachedFirstLookup: (String) -> Void = { _ in
            ready.signal()
            _ = release.wait(timeout: .now() + 20)
        }
        first.debugBeforeFirstKeyAdd = reachedFirstLookup
        second.debugBeforeFirstKeyAdd = reachedFirstLookup
        defer {
            first.debugBeforeFirstKeyAdd = nil
            second.debugBeforeFirstKeyAdd = nil
            first.debugDeleteKey()
            try? FileManager.default.removeItem(at: raceRoot)
        }
        let queue = DispatchQueue(label: "recovery-first-key", attributes: .concurrent)
        queue.async { firstResult.result = first.write(uid: firstUid, plaintext: "first-key-a"); firstDone.signal() }
        queue.async { secondResult.result = second.write(uid: secondUid, plaintext: "first-key-b"); secondDone.signal() }
        let bothSawMissingKey = ready.wait(timeout: .now() + 20) == .success &&
            ready.wait(timeout: .now() + 20) == .success
        release.signal()
        release.signal()
        let bothFinished = firstDone.wait(timeout: .now() + 20) == .success &&
            secondDone.wait(timeout: .now() + 20) == .success
        record("firstKeyConcurrentCreation", bothSawMissingKey && bothFinished && firstResult.result?.failureCode == nil && secondResult.result?.failureCode == nil)
        let restarted = RecoveryPersistenceBridge(rootURL: raceRoot, keyIdentifier: raceKey)
        record("firstKeyRestartBothPartitions", bothFinished && restarted.read(uid: firstUid).payload == "first-key-a" && restarted.read(uid: secondUid).payload == "first-key-b")
    }
}
#endif
