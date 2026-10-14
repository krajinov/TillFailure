package com.delminiusapps.tillfailure.invitations

import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.identity.DepartureOwnershipLock
import com.delminiusapps.tillfailure.persistence.AccountPersistencePrototype
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Safe command identity survives an unknown outcome; bearer tokens never enter this record. */
@Serializable
data class PendingInvitationCommand(
    val uid: String,
    val action: String,
    val idempotencyKey: String,
    val workspaceId: String? = null,
    val invitationId: String? = null,
    val expectedVersion: Int? = null,
    val email: String? = null,
)

@Serializable
data class InvitationRequest(
    val action: String,
    val workspaceId: String? = null,
    val invitationId: String? = null,
    val email: String? = null,
    val token: String? = null,
    val expectedVersion: Int? = null,
    val idempotencyKey: String? = null,
) {
    // Deliberately exclude bearer material from diagnostic interpolation.
    override fun toString(): String = "InvitationRequest(action=$action)"
    fun pending(uid: String): PendingInvitationCommand? = idempotencyKey?.let {
        PendingInvitationCommand(uid, action, it, workspaceId, invitationId, expectedVersion, email)
    }
}

@Serializable
data class InvitationSummary(
    val invitationId: String, val emailMasked: String, val role: String, val status: String,
    val expiresAtMillis: Long, val createdAtMillis: Long, val lastSentAtMillis: Long, val tokenVersion: Int,
)

@Serializable
data class InvitationReply(
    val invitationId: String? = null, val workspaceId: String? = null, val workspaceName: String? = null,
    val status: String? = null, val tokenVersion: Int? = null, val expiresAtMillis: Long? = null,
    val replayed: Boolean? = null, val deliveryToken: String? = null,
    val summaries: List<InvitationSummary>? = null,
) {
    override fun toString(): String = "InvitationReply(status=$status)"
}

data class InvitationOutcome(val reply: InvitationReply? = null, val error: String? = null, val ambiguous: Boolean = false)

interface InvitationClient {
    val memory: InvitationMemory
    fun pending(uid: String): PendingInvitationCommand?
    fun execute(uid: String, request: InvitationRequest, callback: (InvitationOutcome) -> Unit): FirebaseCancellation
    fun newCommandKey(): String
    fun observePending(uid: String, callback: () -> Unit): FirebaseCancellation = FirebaseCancellation {}
}

/** Process-only link context, also retained across a controlled wrong-account departure. */
class InvitationMemory {
    private val lock = DepartureOwnershipLock()
    private var retained: InvitationLink? = null
    private var linkRevision = 0L
    val link: InvitationLink? get() = lock.locked { retained }
    val revision: Long get() = lock.locked { linkRevision }
    // Safe metadata only. The original bearer remains exclusively in this process's link.
    private var acceptance: PendingInvitationCommand? = null
    fun linkFor(uid: String?): InvitationLink? = lock.locked {
        retained?.takeIf { acceptance == null || acceptance?.uid == uid }
    }
    fun protectAcceptance(command: PendingInvitationCommand, issuedToken: String? = null): Boolean = lock.locked {
        if (command.action != "accept" || command.invitationId == null || command.expectedVersion == null) return@locked false
        if (acceptance != null && acceptance != command) return@locked false
        // First issuance captures the request's bearer, even if a confirmation's draft entry
        // changed beforehand. Restored safe metadata alone must never manufacture a bearer.
        val issued = issuedToken?.let { InvitationLink.parse(InvitationLink(command.invitationId, command.expectedVersion, it).entry()) }
        if (issuedToken != null && issued == null) return@locked false
        if (acceptance == null && issued != null) { retained = issued; linkRevision++ }
        acceptance = command
        true
    }
    fun open(value: String, uid: String? = null): Boolean = lock.locked {
        val parsed = InvitationLink.parse(value) ?: return@locked false
        acceptance?.let { pending ->
            if (pending.uid != uid || parsed.invitationId != pending.invitationId || parsed.version != pending.expectedVersion) return@locked false
            // Matching ID/version must not replace a known original bearer with a forged token.
            retained?.takeIf { it.invitationId == pending.invitationId && it.version == pending.expectedVersion }?.let {
                if (it.token != parsed.token) return@locked false
            }
        }
        retained = parsed; linkRevision++
        true
    }
    fun clear(uid: String? = null): Boolean = lock.locked {
        if (acceptance != null && acceptance?.uid != uid) return@locked false
        retained = null; acceptance = null; linkRevision++
        true
    }
    fun consume(uid: String, reply: InvitationReply?) {
        lock.locked {
            if (reply?.status != "accepted") return@locked
            val pending = acceptance
            if (pending != null && (pending.uid != uid || reply.invitationId != pending.invitationId || reply.tokenVersion != pending.expectedVersion)) return@locked
            if (reply.invitationId == retained?.invitationId || pending != null) clear(uid)
        }
    }
}

object PendingInvitationLinks { val memory = InvitationMemory() }

class InvitationLink(val invitationId: String, val version: Int, val token: String) {
    override fun toString(): String = "InvitationLink(redacted)"
    fun entry(): String = "https://invite.tillfailure.invalid/i/$invitationId/$version#$token"
    companion object {
        // Reserved .invalid host is the emulator entry format, not an approved delivery domain.
        fun parse(value: String): InvitationLink? {
            if (value.length > 512) return null
            val match = Regex("^https://invite\\.tillfailure\\.invalid/i/([a-f0-9]{64})/([1-9][0-9]{0,8})#([A-Za-z0-9_-]{43})$").matchEntire(value) ?: return null
            return InvitationLink(match.groupValues[1], match.groupValues[2].toIntOrNull() ?: return null, match.groupValues[3])
        }
    }
}

/** Called within the native issuance/callback lock. Unreadable partitions fail closed. */
class InvitationLedger(private val store: AccountPersistencePrototype) {
    fun pending(uid: String): PendingInvitationCommand? = store.read(uid)?.pendingInvitationCommands?.singleOrNull()
    fun prepare(uid: String, request: InvitationRequest): Boolean = runCatching {
        val record = store.read(uid) ?: return@runCatching false
        if (record.schemaVersion != 1 || record.uid != uid || record.switchMarker != null) return@runCatching false
        val command = request.pending(uid) ?: return@runCatching record.pendingInvitationCommands.isEmpty()
        if (record.pendingInvitationCommands.isNotEmpty() && record.pendingInvitationCommands != listOf(command)) return@runCatching false
        store.write(record.copy(pendingInvitationCommands = listOf(command)))
        true
    }.getOrDefault(false)
    fun settle(uid: String, request: InvitationRequest, outcome: InvitationOutcome): InvitationOutcome = runCatching {
        val command = request.pending(uid) ?: return@runCatching outcome
        // A rejection describes this invocation, not every earlier invocation of this command.
        // In particular, no receipt at throttle/Auth failure time rules out a later commit.
        // Without a validated matching success/receipt, never erase the protected command.
        if (outcome.ambiguous || outcome.error != null || outcome.reply == null)
            return@runCatching outcome.copy(ambiguous = true)
        val record = store.read(uid) ?: return@runCatching InvitationOutcome(error = "unavailable", ambiguous = true)
        if (record.pendingInvitationCommands != listOf(command)) return@runCatching InvitationOutcome(error = "unavailable", ambiguous = true)
        store.write(record.copy(pendingInvitationCommands = emptyList()))
        outcome
    }.getOrDefault(InvitationOutcome(error = "unavailable", ambiguous = true))
}

object InvitationJson {
    val format = Json { explicitNulls = false }
    fun request(value: InvitationRequest): String = format.encodeToString(InvitationRequest.serializer(), value)
    fun callable(request: InvitationRequest, httpStatus: Int, body: String): InvitationOutcome = runCatching {
        val envelope = format.parseToJsonElement(body) as? JsonObject ?: error("envelope")
        if (httpStatus == 200 && envelope.keys == setOf("result")) {
            outcome(request, envelope.getValue("result").toString(), null, false)
        } else {
            require(envelope.keys == setOf("error"))
            val error = envelope.getValue("error") as? JsonObject ?: error("error")
            require(error.keys.all { it in setOf("status", "message", "details") })
            val status = (error["status"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val reason = (error["message"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val expectedStatus = mapOf("INVALID_ARGUMENT" to 400, "FAILED_PRECONDITION" to 400,
                "UNAUTHENTICATED" to 401, "PERMISSION_DENIED" to 403, "RESOURCE_EXHAUSTED" to 429)[status]
            require(expectedStatus != null && expectedStatus == httpStatus && reason != null)
            outcome(request, null, reason, false)
        }
    }.getOrDefault(InvitationOutcome(error = "unavailable", ambiguous = true))
    fun outcome(request: InvitationRequest, json: String?, error: String?, ambiguous: Boolean): InvitationOutcome = if (error != null)
        InvitationOutcome(error = safeError(error), ambiguous = ambiguous || error == "idempotency-key-reused" || safeError(error) == "unavailable")
    else runCatching {
        val reply = format.decodeFromString(InvitationReply.serializer(), json ?: "")
        require(validReply(request, reply))
        InvitationOutcome(reply = reply)
    }
        .getOrDefault(InvitationOutcome(error = "unavailable", ambiguous = true))
    private fun validReply(request: InvitationRequest, reply: InvitationReply): Boolean {
        fun id(value: String?) = value != null && Regex("^[a-f0-9]{64}$").matches(value)
        fun workspace(value: String?) = value != null && com.delminiusapps.tillfailure.identity.isPathSafeAuthUid(value) && !value.contains('.')
        fun time(value: Long?) = value != null && value in 0..8_640_000_000_000_000L
        if (request.action == "list") return reply.summaries != null && reply.summaries.size <= 20 &&
            reply.invitationId == null && reply.workspaceId == null && reply.workspaceName == null && reply.status == null &&
            reply.tokenVersion == null && reply.expiresAtMillis == null && reply.replayed == null && reply.deliveryToken == null &&
            reply.summaries.all { id(it.invitationId) && it.role == "client" && it.status in setOf("pending", "accepted", "revoked") &&
                it.tokenVersion > 0 && it.emailMasked.length <= 257 &&
                Regex("^.+\\*\\*\\*@[^\\s@]+\\.[^\\s@]+$").matches(it.emailMasked) &&
                time(it.createdAtMillis) && time(it.lastSentAtMillis) && time(it.expiresAtMillis) }
        if (!id(reply.invitationId) || !workspace(reply.workspaceId) || !time(reply.expiresAtMillis) ||
            reply.summaries != null || request.invitationId != null && reply.invitationId != request.invitationId ||
            request.workspaceId != null && reply.workspaceId != request.workspaceId) return false
        val version = when (request.action) { "create" -> 1; "resend" -> request.expectedVersion?.plus(1); else -> request.expectedVersion }
        if (reply.tokenVersion != version || version == null || version <= 0) return false
        if (request.action == "preview") return reply.status == "pending" && reply.replayed == null && reply.deliveryToken == null &&
            reply.workspaceName != null && reply.workspaceName.isNotBlank() && reply.workspaceName.length <= 80
        if (reply.replayed == null || reply.workspaceName != null) return false
        return when (request.action) {
            "create", "resend" -> reply.status == "pending" && if (reply.replayed) reply.deliveryToken == null
                else reply.deliveryToken != null && Regex("^[A-Za-z0-9_-]{43}$").matches(reply.deliveryToken)
            "accept" -> reply.status == "accepted" && reply.deliveryToken == null
            "revoke" -> reply.status == "revoked" && reply.deliveryToken == null
            else -> false
        }
    }
    fun safeError(value: String): String = if (value in setOf("unauthenticated", "unauthorized", "unverified-email", "wrong-account-email",
        "expired", "rotated", "revoked", "already-used", "idempotency-key-reused", "membership-conflict", "rate-limited",
        "fixture-account-required", "record-invalid", "counter-inconsistent", "input-invalid", "email-invalid", "token-invalid",
        "membership-bound-violated", "workspace-roster-bound-violated", "invitation-unavailable")) value else "unavailable"
}
