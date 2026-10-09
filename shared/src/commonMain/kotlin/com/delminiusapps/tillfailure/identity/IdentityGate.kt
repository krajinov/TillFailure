package com.delminiusapps.tillfailure.identity

import com.delminiusapps.tillfailure.firebase.FirebaseAuthSession
import com.delminiusapps.tillfailure.firebase.FirebaseDataOrigin
import com.delminiusapps.tillfailure.firebase.FirebaseDocumentSnapshot

/**
 * The explicit unverified-email gate precondition: a non-anonymous email/password session whose
 * freshly checked Auth user is not verified stops before any account/workspace authorization. Shared
 * so both native hosts can prove the outcome natively rather than only from a cached flag.
 */
fun requiresUnverifiedEmailGate(session: FirebaseAuthSession): Boolean =
    !session.isAnonymous && session.email != null && !session.emailVerified

/** Firebase Auth UIDs remain raw document IDs for the MVP. Never rewrite an unsupported UID. */
fun isPathSafeAuthUid(uid: String?): Boolean =
    uid != null && uid.isNotBlank() && uid.length <= 128 &&
        uid != "." && uid != ".." && '/' !in uid &&
        !(uid.startsWith("__") && uid.endsWith("__")) && uid.hasValidUtf16()

private fun String.hasValidUtf16(): Boolean {
    var index = 0
    while (index < length) {
        val current = this[index]
        when {
            current.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }
            current.isLowSurrogate() -> return false
            else -> index++
        }
    }
    return true
}

private fun safeSegment(segment: String): Boolean =
    segment.isNotBlank() && segment != "." && segment != ".." &&
        '/' !in segment && !(segment.startsWith("__") && segment.endsWith("__")) &&
        segment.hasValidUtf16()

sealed interface IdentityDecision {
    data class Verified(val workspaceId: String, val role: String) : IdentityDecision
    data object NoAccount : IdentityDecision
    data object AccountDisabled : IdentityDecision
    data object UnsupportedSchema : IdentityDecision
    data object InvalidProof : IdentityDecision
}

private fun serverDocument(document: FirebaseDocumentSnapshot?, path: String): Boolean =
    document != null && document.path == path && document.origin == FirebaseDataOrigin.SERVER &&
        !document.hasPendingWrites

fun checkAccount(uid: String, account: FirebaseDocumentSnapshot?): IdentityDecision? {
    if (!serverDocument(account, "users/$uid")) return IdentityDecision.InvalidProof
    if (!account!!.exists) return IdentityDecision.NoAccount
    if (account.fields["schemaVersion"] != "1") return IdentityDecision.UnsupportedSchema
    return when (account.fields["accountStatus"]) {
        "active" -> null
        "disabled" -> IdentityDecision.AccountDisabled
        else -> IdentityDecision.InvalidProof
    }
}

/** Each record is independently checked even after a constrained discovery query. */
fun checkMembership(
    uid: String,
    workspaceId: String,
    workspace: FirebaseDocumentSnapshot?,
    membership: FirebaseDocumentSnapshot?,
): IdentityDecision {
    if (!safeSegment(workspaceId)) return IdentityDecision.InvalidProof
    val workspacePath = "workspaces/$workspaceId"
    val membershipPath = "$workspacePath/memberships/$uid"
    if (!serverDocument(workspace, workspacePath) || !serverDocument(membership, membershipPath)) {
        return IdentityDecision.InvalidProof
    }
    if (!workspace!!.exists || !membership!!.exists) return IdentityDecision.InvalidProof
    if (workspace.fields["schemaVersion"] != "1" || membership.fields["schemaVersion"] != "1") {
        return IdentityDecision.UnsupportedSchema
    }
    if (workspace.fields["status"] != "active" ||
        membership.fields["status"] != "active" || membership.fields["workspaceId"] != workspaceId ||
        membership.fields["userId"] != uid
    ) return IdentityDecision.InvalidProof
    val role = membership.fields["role"]
    if (role != "client" && role != "trainer") return IdentityDecision.InvalidProof
    return IdentityDecision.Verified(workspaceId, role)
}
