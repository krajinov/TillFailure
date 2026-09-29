package com.delminiusapps.tillfailure.persistence

/**
 * Cross-platform recovery-partition UID contract shared by the Android persistence
 * implementation and the Apple bridge.
 *
 * The authoritative Firebase Auth contract accepts any non-empty user identifier of at most
 * 128 characters; the Firebase Admin SDK enforces that bound with its own string-length
 * check, which counts UTF-16 code units. Both platforms therefore validate with exactly this
 * rule and never impose a filename-safe character whitelist: the raw UID is hashed into a
 * fixed-length partition file name, so email-shaped, punctuated, and Unicode UIDs are all
 * accepted safely. Blank identifiers are rejected as an app-level sanity rule so the
 * platform layers agree with [AccountPersistenceEnvelope].
 */
object RecoveryUidContract {
    /**
     * Maximum accepted UID length in UTF-16 code units. This matches the Firebase Auth/Admin
     * SDK identifier bound and is counted identically by Kotlin `String.length` and Swift
     * `utf16.count`, so both platforms accept and reject the same identifiers.
     */
    const val MAX_UID_UTF16_LENGTH: Int = 128

    fun isValid(uid: String): Boolean = uid.isNotBlank() && uid.length <= MAX_UID_UTF16_LENGTH

    fun requireValid(uid: String) {
        require(isValid(uid)) {
            "Recovery UID must be non-blank and at most $MAX_UID_UTF16_LENGTH UTF-16 code units"
        }
    }
}
