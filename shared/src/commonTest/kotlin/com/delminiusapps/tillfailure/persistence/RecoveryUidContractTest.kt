package com.delminiusapps.tillfailure.persistence

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryUidContractTest {
    @Test
    fun acceptsTheCompleteFirebaseUidDomain() {
        assertTrue(RecoveryUidContract.isValid("uid_one"))
        assertTrue(RecoveryUidContract.isValid("user@example.com"))
        assertTrue(RecoveryUidContract.isValid("user+alias@example.com"))
        assertTrue(RecoveryUidContract.isValid("a.b-c_d:e|f!g"))
        assertTrue(RecoveryUidContract.isValid("üser-Ω"))
        assertTrue(RecoveryUidContract.isValid("../../etc/passwd"))
        assertTrue(RecoveryUidContract.isValid("a".repeat(RecoveryUidContract.MAX_UID_UTF16_LENGTH)))
    }

    @Test
    fun rejectsEmptyBlankAndOverlengthIdentifiers() {
        assertFalse(RecoveryUidContract.isValid(""))
        assertFalse(RecoveryUidContract.isValid("   "))
        assertFalse(RecoveryUidContract.isValid("a".repeat(RecoveryUidContract.MAX_UID_UTF16_LENGTH + 1)))
        // The bound is measured in UTF-16 code units, matching the Firebase Auth/Admin SDK
        // length check, so an astral code point counts as two units.
        val astral = "\uD83D\uDE00"
        assertTrue(RecoveryUidContract.isValid(astral.repeat(64)))
        assertFalse(RecoveryUidContract.isValid(astral.repeat(65)))
    }

    @Test
    fun neverNormalizesDistinctValidIdentifiers() {
        val identifiers = listOf("user@example.com", "USER@example.com", "user@example.com ", " user@example.com")
        assertTrue(identifiers.all { RecoveryUidContract.isValid(it) })
        assertTrue(identifiers.toSet().size == identifiers.size)
    }
}
