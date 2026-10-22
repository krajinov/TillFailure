package com.delminiusapps.tillfailure.invitations

import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.identity.DepartureOwnershipLock
import com.delminiusapps.tillfailure.persistence.AccountPersistencePrototype
import com.delminiusapps.tillfailure.profile.BasicProfile
import com.delminiusapps.tillfailure.profile.ProfileUpdate
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
    val onboarding: com.delminiusapps.tillfailure.onboarding.OnboardingCompletion? = null,
    val profileUpdate: ProfileUpdate? = null,
    val acceptanceTokenDigest: String? = null,
    val resolutionStarted: Boolean = false,
) { override fun toString() = "PendingInvitationCommand(redacted)" }

@Serializable
data class InvitationRequest(
    val action: String,
    val workspaceId: String? = null,
    val invitationId: String? = null,
    val email: String? = null,
    val token: String? = null,
    val expectedVersion: Int? = null,
    val idempotencyKey: String? = null,
    val onboarding: com.delminiusapps.tillfailure.onboarding.OnboardingCompletion? = null,
    val profileUpdate: ProfileUpdate? = null,
    val resolution: PendingInvitationCommand? = null,
) {
    // Preserve the existing Swift bridge constructor while extending the protected protocol.
    constructor(action: String, workspaceId: String?, invitationId: String?, email: String?, token: String?,
        expectedVersion: Int?, idempotencyKey: String?, onboarding: com.delminiusapps.tillfailure.onboarding.OnboardingCompletion?) :
        this(action, workspaceId, invitationId, email, token, expectedVersion, idempotencyKey, onboarding, null)
    constructor(action: String, workspaceId: String?, invitationId: String?, email: String?, token: String?,
        expectedVersion: Int?, idempotencyKey: String?, onboarding: com.delminiusapps.tillfailure.onboarding.OnboardingCompletion?, profileUpdate: ProfileUpdate?) :
        this(action, workspaceId, invitationId, email, token, expectedVersion, idempotencyKey, onboarding, profileUpdate, null)
    // Deliberately exclude bearer material from diagnostic interpolation.
    override fun toString(): String = "InvitationRequest(action=$action)"
    fun pending(uid: String): PendingInvitationCommand? = if (action == "resolve-command") resolution?.takeIf { it.uid == uid } else idempotencyKey?.let {
        PendingInvitationCommand(uid, action, it, workspaceId, invitationId, expectedVersion, email, onboarding, profileUpdate,
            if (action == "accept" && it.startsWith("or1-") && token != null) commandDigest(token) else null)
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
    val accountRevision: Int? = null, val onboardingRevision: Int? = null,
    val terminalOutcome: CommandResolutionReply? = null,
    val profile: BasicProfile? = null, val profileCommandKey: String? = null,
) {
    override fun toString(): String = "InvitationReply(status=$status)"
}

data class InvitationOutcome(val reply: InvitationReply? = null, val error: String? = null, val ambiguous: Boolean = false)

enum class ProtectedRecoveryState { Clean, PendingCommand, StorageLocked, ReconciliationRequired }

interface InvitationClient {
    fun recoveryState(uid: String): ProtectedRecoveryState = try {
        if (pending(uid) == null) ProtectedRecoveryState.Clean else ProtectedRecoveryState.PendingCommand
    } catch (_: Exception) { ProtectedRecoveryState.StorageLocked }
    val memory: InvitationMemory
    fun pending(uid: String): PendingInvitationCommand?
    fun execute(uid: String, request: InvitationRequest, callback: (InvitationOutcome) -> Unit): FirebaseCancellation
    fun lastSettled(uid: String): CommandTerminalProof? = null
    fun workspacePreference(uid: String): String? = null
    fun saveWorkspacePreference(uid: String, workspaceId: String) {}
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
        // changed beforehand. On metadata-only restoration, any earlier entry is unverified:
        // matching ID/version cannot prove that its bearer belongs to the original command.
        val issued = issuedToken?.let { InvitationLink.parse(InvitationLink(command.invitationId, command.expectedVersion, it).entry()) }
        if (issuedToken != null && issued == null) return@locked false
        if (acceptance == null) {
            if (retained != null || issued != null) { retained = issued; linkRevision++ }
        }
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
    fun consume(uid: String, reply: InvitationReply?, proof: CommandTerminalProof? = null) {
        lock.locked {
            if (proof != null) {
                val pending = acceptance
                if (proof.command.uid == uid && proof.valid() && pending?.uid == uid && CommandResolution.matches(pending, proof.command)) clear(uid)
                return@locked
            }
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
    internal var beforeRetirementForTest: (() -> Unit)? = null
    private val lock = DepartureOwnershipLock()
    private fun record(uid: String): com.delminiusapps.tillfailure.persistence.AccountPersistenceEnvelope {
        val record = store.read(uid) ?: error("Missing protected registry")
        require(record.schemaVersion == 1 && record.uid == uid)
        require(record.pendingInvitationCommands.size <= 1 && record.pendingInvitationCommands.all { validPendingCommand(uid, it) })
        require(record.pendingTerminalProof?.let { it.valid() && record.pendingInvitationCommands.singleOrNull()?.let { p -> CommandResolution.matches(p,it.command) } == true } != false)
        require(record.lastSettledCommand?.let { it.command.uid == uid && it.valid() } != false)
        if (record.pendingTerminalProof != null) {
            val retired = record.copy(pendingInvitationCommands=emptyList(), pendingTerminalProof=null, lastSettledCommand=record.pendingTerminalProof)
            store.write(retired)
            return retired
        }
        return record
    }
    fun recoveryState(uid: String): ProtectedRecoveryState = lock.locked { try {
        val r = record(uid)
        when {
            r.switchMarker != null || r.offlineAccessGrant != null || r.downloadManifests.isNotEmpty() ||
                r.workoutRecoverySnapshot != null || r.mutationJournal.isNotEmpty() || r.pendingUploads.isNotEmpty() -> ProtectedRecoveryState.ReconciliationRequired
            r.pendingInvitationCommands.isNotEmpty() -> ProtectedRecoveryState.PendingCommand
            else -> ProtectedRecoveryState.Clean
        }
    } catch (_: Exception) { ProtectedRecoveryState.StorageLocked } }
    fun workspacePreference(uid: String): String? = lock.locked { record(uid).preferredWorkspaceId }
    fun saveWorkspacePreference(uid: String, workspaceId: String) = lock.locked {
        val record = record(uid)
        if (record.switchMarker == null) store.write(record.copy(preferredWorkspaceId = workspaceId))
    }
    fun lastSettled(uid: String): CommandTerminalProof? = lock.locked { record(uid).lastSettledCommand }
    fun pending(uid: String): PendingInvitationCommand? = lock.locked { record(uid).pendingInvitationCommands.singleOrNull() }
    fun prepare(uid: String, request: InvitationRequest): Boolean = lock.locked { runCatching {
        val record = record(uid)
        if (recoveryState(uid) !in setOf(ProtectedRecoveryState.Clean, ProtectedRecoveryState.PendingCommand)) return@runCatching false
        val command = request.pending(uid) ?: return@runCatching request.action in setOf("list", "preview", "profile-get") && record.pendingInvitationCommands.isEmpty()
        if (!validPendingCommand(uid, command)) return@runCatching false
        val old = record.pendingInvitationCommands.singleOrNull()
        if (old == null && record.lastSettledCommand?.command?.idempotencyKey == command.idempotencyKey) return@runCatching false
        if (request.action == "resolve-command") {
            if (old == null || !CommandResolution.matches(old,command) || !CommandResolution.bound(command)) return@runCatching false
            store.write(record.copy(pendingInvitationCommands=listOf(old.copy(resolutionStarted=true))))
        } else {
            if (old?.resolutionStarted == true || old != null && !CommandResolution.matches(old,command)) return@runCatching false
            store.write(record.copy(pendingInvitationCommands = listOf(old ?: command)))
        }
        true
    }.getOrDefault(false) }
    fun settle(uid: String, request: InvitationRequest, outcome: InvitationOutcome): InvitationOutcome = lock.locked { runCatching {
        val command = request.pending(uid) ?: return@runCatching outcome
        // A rejection describes this invocation, not every earlier invocation of this command.
        // In particular, no receipt at throttle/Auth failure time rules out a later commit.
        // Without a validated matching success/receipt, never erase the protected command.
        if (outcome.ambiguous || outcome.error != null || outcome.reply == null || !InvitationJson.validReply(request, outcome.reply))
            return@runCatching InvitationOutcome(error = outcome.error ?: "unavailable", ambiguous = true)
        val record = record(uid)
        val old = record.pendingInvitationCommands.singleOrNull() ?: return@runCatching InvitationOutcome(error="unavailable",ambiguous=true)
        if (!CommandResolution.matches(old,command)) return@runCatching InvitationOutcome(error="unavailable",ambiguous=true)
        val proofCommand = if (command.action == "accept" && command.acceptanceTokenDigest == null && request.token != null)
            command.copy(acceptanceTokenDigest=commandDigest(request.token)) else command
        val proof = if (request.action == "resolve-command") CommandTerminalProof(proofCommand,"resolution-v1", resolution=outcome.reply.terminalOutcome)
            else CommandTerminalProof(proofCommand,"normal-v1",acknowledgment=outcome.reply.copy(deliveryToken=null))
        if (!proof.valid()) return@runCatching InvitationOutcome(error="unavailable",ambiguous=true)
        store.write(record.copy(pendingTerminalProof=proof))
        beforeRetirementForTest?.invoke()
        store.write(record.copy(pendingInvitationCommands=emptyList(),pendingTerminalProof=null,lastSettledCommand=proof))
        outcome
    }.getOrDefault(InvitationOutcome(error = "storage-unavailable", ambiguous = true)) }
}

/** Reject damaged or future command shapes before exposing or rewriting the protected partition. */
internal fun validPendingCommand(uid: String, p: PendingInvitationCommand): Boolean {
    fun segment(v: String?) = v != null && com.delminiusapps.tillfailure.identity.isPathSafeAuthUid(v) && '.' !in v
    fun invitation() = p.invitationId != null && Regex("^[a-f0-9]{64}$").matches(p.invitationId)
    if (p.action == "accept" && p.idempotencyKey.startsWith("or1-") && p.acceptanceTokenDigest == null) return false
    if (p.acceptanceTokenDigest != null && (p.action != "accept" || !Regex("^[a-f0-9]{64}$").matches(p.acceptanceTokenDigest))) return false
    if (p.uid != uid || !segment(p.idempotencyKey) || p.action != "update-profile" && p.profileUpdate != null) return false
    return when (p.action) {
        "create" -> segment(p.workspaceId) && p.invitationId == null && p.expectedVersion == null && p.onboarding == null &&
            p.email != null && p.email.length <= 254 && Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(p.email)
        "accept" -> p.workspaceId == null && invitation() && p.expectedVersion in 1..Int.MAX_VALUE && p.email == null && p.onboarding == null
        "resend", "revoke" -> segment(p.workspaceId) && invitation() && p.expectedVersion in 1..Int.MAX_VALUE && p.email == null && p.onboarding == null
        "update-profile" -> segment(p.workspaceId) && p.invitationId == null && p.expectedVersion == null && p.email == null &&
            p.onboarding == null && p.profileUpdate?.valid() == true
        "complete-onboarding" -> segment(p.workspaceId) && p.invitationId == null && p.expectedVersion == null && p.email == null && p.onboarding?.let {
            it.acknowledged && it.expectedAccountRevision in 0..Int.MAX_VALUE - 2 && it.expectedOnboardingRevision in 0..Int.MAX_VALUE - 2 &&
                com.delminiusapps.tillfailure.onboarding.validOnboardingBasics(it.displayName, it.locale, it.timeZoneId)
        } == true
        else -> false
    }
}

object InvitationJson {
    val format = Json { explicitNulls = false }
    fun request(value: InvitationRequest): String = if (value.action == "resolve-command") CommandResolution.request(value.resolution ?: error("binding-required")) else format.encodeToString(InvitationRequest.serializer(), value)
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
    else if (ambiguous) InvitationOutcome(error = "unavailable", ambiguous = true)
    else runCatching {
        val reply = if (request.action == "resolve-command") InvitationReply(terminalOutcome=CommandResolution.parse(request.resolution ?: error("binding-required"),json) ?: error("outcome-invalid"))
            else format.decodeFromString(InvitationReply.serializer(), json ?: "")
        require(validReply(request, reply))
        InvitationOutcome(reply = reply)
    }
        .getOrDefault(InvitationOutcome(error = "unavailable", ambiguous = true))
    fun validReply(request: InvitationRequest, reply: InvitationReply): Boolean {
        if (request.action == "resolve-command") return request.resolution?.let { p -> reply.terminalOutcome?.let { CommandResolution.valid(p,it) } } == true && reply.copy(terminalOutcome=null) == InvitationReply()
        if (reply.terminalOutcome != null || request.resolution != null) return false
        if (request.action in setOf("profile-get", "update-profile")) {
            val workspace = request.workspaceId ?: return false
            if (!com.delminiusapps.tillfailure.identity.isPathSafeAuthUid(workspace) || '.' in workspace ||
                request.invitationId != null || request.email != null || request.token != null || request.expectedVersion != null || request.onboarding != null) return false
            if (request.action == "profile-get" && (request.idempotencyKey != null || request.profileUpdate != null)) return false
            if (request.action == "update-profile" && (request.idempotencyKey == null ||
                    !com.delminiusapps.tillfailure.identity.isPathSafeAuthUid(request.idempotencyKey) || '.' in request.idempotencyKey)) return false
            if (reply.workspaceId != request.workspaceId || reply.invitationId != null || reply.workspaceName != null ||
                reply.tokenVersion != null || reply.expiresAtMillis != null || reply.deliveryToken != null ||
                reply.summaries != null || reply.onboardingRevision != null) return false
            if (request.action == "profile-get") return reply.status == "profile" && reply.profile?.valid() == true &&
                reply.replayed == null && reply.accountRevision == null && reply.profileCommandKey == null
            val p = request.profileUpdate ?: return false
            return p.valid() && reply.profile == null && reply.replayed != null && reply.profileCommandKey == request.idempotencyKey &&
                reply.accountRevision != null && reply.accountRevision in 1..Int.MAX_VALUE - 1 && when (reply.status) {
                    "profile-updated" -> reply.accountRevision == p.expectedRevision + 1
                    "profile-conflict" -> reply.accountRevision > p.expectedRevision
                    else -> false
                }
        }
        if (reply.profile != null || reply.profileCommandKey != null) return false
        if (request.action == "complete-onboarding") {
            val p = request.onboarding ?: return false
            return reply.workspaceId == request.workspaceId && reply.status == "complete" && reply.replayed != null &&
                reply.accountRevision == p.expectedAccountRevision + 1 && reply.onboardingRevision == p.expectedOnboardingRevision + 1 &&
                reply.invitationId == null && reply.workspaceName == null && reply.tokenVersion == null && reply.expiresAtMillis == null &&
                reply.deliveryToken == null && reply.summaries == null
        }
        if (reply.accountRevision != null || reply.onboardingRevision != null) return false
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
    fun safeError(value: String): String = if (value in setOf("identity-required", "outcome-unavailable", "rate-limited", "unauthenticated", "unauthorized", "unverified-email", "wrong-account-email",
        "expired", "rotated", "revoked", "already-used", "idempotency-key-reused", "membership-conflict", "rate-limited",
        "already-complete", "revision-conflict", "fixture-account-required", "record-invalid", "counter-inconsistent", "input-invalid", "email-invalid", "token-invalid",
        "membership-bound-violated", "workspace-roster-bound-violated", "invitation-unavailable")) value else "unavailable"
}
