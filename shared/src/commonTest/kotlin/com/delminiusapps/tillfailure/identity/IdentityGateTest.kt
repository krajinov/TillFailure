package com.delminiusapps.tillfailure.identity

import com.delminiusapps.tillfailure.firebase.FirebaseDataOrigin
import com.delminiusapps.tillfailure.firebase.FirebaseDocumentSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityGateTest {
    @Test fun rawUidIsPreservedOnlyWhenItIsOneSafeFirestoreSegment() {
        for (uid in listOf("a.b+tag@domain", "é中🧭", "_", "x".repeat(128))) assertTrue(isPathSafeAuthUid(uid))
        for (uid in listOf(null, "", "   ", ".", "..", "a/b", "__reserved__", "x".repeat(129), "\uD83E")) {
            assertFalse(isPathSafeAuthUid(uid))
        }
        assertFalse(isPathSafeAuthUid("🧭".repeat(65))) // 130 UTF-16 units
    }

    @Test fun accountRequiresExactServerProof() {
        val account = document("users/a", mapOf("schemaVersion" to "1", "accountStatus" to "active"))
        assertNull(checkAccount("a", account))
        assertEquals(IdentityDecision.InvalidProof, checkAccount("a", account.copy(origin = FirebaseDataOrigin.CACHE)))
        assertEquals(IdentityDecision.InvalidProof, checkAccount("a", account.copy(hasPendingWrites = true)))
        assertEquals(IdentityDecision.InvalidProof, checkAccount("b", account))
        assertEquals(IdentityDecision.NoAccount, checkAccount("a", account.copy(exists = false)))
        assertEquals(IdentityDecision.AccountDisabled, checkAccount("a", account.copy(fields = account.fields + ("accountStatus" to "disabled"))))
        assertEquals(IdentityDecision.UnsupportedSchema, checkAccount("a", account.copy(fields = account.fields + ("schemaVersion" to "2"))))
    }

    @Test fun membershipRequiresMatchingWorkspaceRoleAndServerDocuments() {
        val workspace = document("workspaces/w", mapOf("schemaVersion" to "1", "status" to "active"))
        val member = document("workspaces/w/memberships/a", mapOf(
            "schemaVersion" to "1", "workspaceId" to "w", "userId" to "a",
            "status" to "active", "role" to "client",
        ))
        assertEquals(IdentityDecision.Verified("w", "client"), checkMembership("a", "w", workspace, member))
        assertEquals(IdentityDecision.InvalidProof, checkMembership("b", "w", workspace, member))
        assertEquals(IdentityDecision.InvalidProof, checkMembership("a", "w", workspace, member.copy(fields = member.fields + ("role" to "admin"))))
        assertEquals(IdentityDecision.InvalidProof, checkMembership("a", "w", workspace, member.copy(fields = member.fields + ("workspaceId" to "other"))))
        assertEquals(IdentityDecision.InvalidProof, checkMembership("a", "w", workspace, member.copy(origin = FirebaseDataOrigin.CACHE)))
    }

    private fun document(path: String, fields: Map<String, String>) = FirebaseDocumentSnapshot(
        path, fields, true, FirebaseDataOrigin.SERVER, false,
    )
}
