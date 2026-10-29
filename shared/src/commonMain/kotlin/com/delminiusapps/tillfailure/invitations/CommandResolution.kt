@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.delminiusapps.tillfailure.invitations

import com.delminiusapps.tillfailure.exercises.*
import com.delminiusapps.tillfailure.programs.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

const val COMMAND_RESOLUTION_GENERATION = "b91f0176ec74475e8cf7406e25b7e9d3"
fun resolutionCommandKey(nonce: String) = "or1-$COMMAND_RESOLUTION_GENERATION-$nonce"

/** Small platform-independent SHA-256 for protocol fingerprints, never an authorization credential. */
internal fun commandDigest(value: String): String {
    val k = longArrayOf(0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2).map { it.toInt() }
    val source = value.encodeToByteArray(); val bytes = ByteArray(((source.size + 9 + 63) / 64) * 64)
    source.copyInto(bytes); bytes[source.size] = 0x80.toByte()
    for (i in 0..7) bytes[bytes.lastIndex - i] = ((source.size.toLong() * 8) ushr (8 * i)).toByte()
    val h = longArrayOf(0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19).map { it.toInt() }.toIntArray()
    fun r(x: Int, n: Int) = (x ushr n) or (x shl (32 - n))
    for (offset in bytes.indices step 64) {
        val w = IntArray(64)
        for (i in 0..15) for (j in 0..3) w[i] = (w[i] shl 8) or (bytes[offset + i * 4 + j].toInt() and 255)
        for (i in 16..63) w[i] = w[i-16] + (r(w[i-15],7) xor r(w[i-15],18) xor (w[i-15] ushr 3)) + w[i-7] + (r(w[i-2],17) xor r(w[i-2],19) xor (w[i-2] ushr 10))
        var a=h[0]; var b=h[1]; var c=h[2]; var d=h[3]; var e=h[4]; var f=h[5]; var g=h[6]; var z=h[7]
        for (i in 0..63) {
            val t=z+(r(e,6) xor r(e,11) xor r(e,25))+((e and f) xor (e.inv() and g))+k[i]+w[i]
            val u=(r(a,2) xor r(a,13) xor r(a,22))+((a and b) xor (a and c) xor (b and c))
            z=g; g=f; f=e; e=d+t; d=c; c=b; b=a; a=t+u
        }
        intArrayOf(a,b,c,d,e,f,g,z).forEachIndexed { i, v -> h[i] += v }
    }
    return h.joinToString("") { it.toUInt().toString(16).padStart(8,'0') }
}
internal fun canonical(e: JsonElement): String = when(e) {
    is JsonObject -> e.keys.sorted().joinToString(",", "{", "}") { JsonPrimitive(it).toString() + ":" + canonical(e.getValue(it)) }
    is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
    else -> e.toString()
}

@Serializable
data class CommandResolutionReply(val protocolVersion: Int, val commandKind: String, val commandId: String,
    val requestHashVersion: String, val requestHash: String, val outcome: String, val decidedAtMillis: Long) {
    override fun toString() = "CommandResolutionReply(redacted)"
}
object CommandResolution {
    fun resolveRequest(p: PendingInvitationCommand) = InvitationRequest("resolve-command",resolution=p)
    fun bound(p: PendingInvitationCommand): Boolean = validPendingCommand(p.uid, p) && (p.action != "accept" || p.acceptanceTokenDigest != null)
    fun descriptor(p: PendingInvitationCommand): JsonObject = buildJsonObject {
        put("action", p.action); put("idempotencyKey", p.idempotencyKey)
        p.workspaceId?.let { put("workspaceId", it) }; p.invitationId?.let { put("invitationId",it) }
        p.expectedVersion?.let { put("expectedVersion",it) }; p.email?.let { put("email",it.lowercase()) }
        p.onboarding?.let { put("onboarding", InvitationJson.format.encodeToJsonElement(it)) }
        p.revocation?.let { put("revocation", InvitationJson.format.encodeToJsonElement(it)) }
        p.noteSave?.let { put("noteSave", InvitationJson.format.encodeToJsonElement(it)) }
        p.draftSave?.let { put("draftSave", DraftSerialization.element(it)) }
        p.exerciseWrite?.let { put("exerciseWrite", InvitationJson.format.encodeToJsonElement(it)) }
        p.profileUpdate?.let { put("profileUpdate", InvitationJson.format.encodeToJsonElement(it)) }
        if (p.action == "accept") put("acceptanceTokenDigest", p.acceptanceTokenDigest ?: error("binding-required"))
    }
    fun request(p: PendingInvitationCommand): String = buildJsonObject { put("protocolVersion",1); put("descriptor",descriptor(p)) }.toString()
    fun kind(p: PendingInvitationCommand) = when(p.action) { "save-program-draft" -> "program-draft-save"; "create-custom-exercise" -> "custom-exercise-create"; "correct-custom-exercise" -> "custom-exercise-correct"; "save-trainer-note" -> "trainer-note-save"; "revoke-client" -> "client-membership-revoke"; "complete-onboarding" -> "onboarding-complete"; "update-profile" -> "profile-update"; else -> "invitation-${p.action}" }
    fun version(p: PendingInvitationCommand) = when(p.action) { "save-program-draft" -> "program-draft-v1"; "create-custom-exercise", "correct-custom-exercise" -> "custom-exercise-v1"; "save-trainer-note" -> "trainer-note-v1"; "revoke-client" -> "client-revocation-v1"; "complete-onboarding" -> "onboarding-v1"; "update-profile" -> "profile-v1"; else -> "invitation-v1" }
    fun id(p: PendingInvitationCommand) = commandDigest(canonical(buildJsonObject { put("callerUid",p.uid); put("idempotencyKey",p.idempotencyKey) }))
    fun hash(p: PendingInvitationCommand): String {
        val data = descriptor(p).toMutableMap(); data.remove("acceptanceTokenDigest"); data["callerUid"] = JsonPrimitive(p.uid)
        if (p.action in setOf("create","resend","revoke","accept")) data["token"] = p.acceptanceTokenDigest?.let(::JsonPrimitive) ?: JsonNull
        else data["policyVersion"] = JsonPrimitive(1)
        return commandDigest(canonical(JsonObject(data)))
    }
    fun matches(original: PendingInvitationCommand, binding: PendingInvitationCommand): Boolean =
        original.copy(resolutionStarted = false) == binding.copy(resolutionStarted = false,
            acceptanceTokenDigest = if (original.acceptanceTokenDigest == null && !original.idempotencyKey.startsWith("or1-")) null else binding.acceptanceTokenDigest)
    fun valid(p: PendingInvitationCommand, r: CommandResolutionReply): Boolean = bound(p) && r.protocolVersion == 1 &&
        r.commandKind == kind(p) && r.commandId == id(p) && r.requestHashVersion == version(p) && r.requestHash == hash(p) &&
        r.outcome in setOf("applied","not-applied-conflict","not-applied-fenced") &&
        (r.outcome != "not-applied-conflict" || p.action in setOf("save-program-draft", "update-profile", "revoke-client", "save-trainer-note", "create-custom-exercise", "correct-custom-exercise")) && r.decidedAtMillis in 0..8_640_000_000_000_000L
    fun parse(p: PendingInvitationCommand, json: String?): CommandResolutionReply? = runCatching {
        val value = InvitationJson.format.parseToJsonElement(json ?: "") as? JsonObject ?: error("outcome-invalid")
        require(value.keys == setOf("protocolVersion", "commandKind", "commandId", "requestHashVersion", "requestHash", "outcome", "decidedAtMillis"))
        for (key in listOf("protocolVersion", "decidedAtMillis")) {
            val number = value[key] as? JsonPrimitive ?: error("outcome-invalid")
            require(!number.isString && number.longOrNull != null)
        }
        for (key in listOf("commandKind", "commandId", "requestHashVersion", "requestHash", "outcome"))
            require((value[key] as? JsonPrimitive)?.isString == true)
        InvitationJson.format.decodeFromJsonElement<CommandResolutionReply>(value).also { require(valid(p,it)) }
    }.getOrNull()
    fun original(p: PendingInvitationCommand) = InvitationRequest(p.action, workspaceId=p.workspaceId, invitationId=p.invitationId,
        email=p.email, expectedVersion=p.expectedVersion, idempotencyKey=p.idempotencyKey, onboarding=p.onboarding, profileUpdate=p.profileUpdate,revocation=p.revocation,noteSave=p.noteSave,exerciseWrite=p.exerciseWrite,draftSave=p.draftSave)
}

@Serializable
data class CommandTerminalProof(val command: PendingInvitationCommand? = null, val source: String,
    val resolution: CommandResolutionReply? = null, val acknowledgment: InvitationReply? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val marker: SettledCommandMarker? = null) {
    override fun toString() = "CommandTerminalProof(redacted)"
    fun ownedBy(uid: String) = valid() && (command?.uid ?: marker?.uid) == uid
    fun consumed(p: PendingInvitationCommand) = ownedBy(p.uid) &&
        (command?.idempotencyKey == p.idempotencyKey || marker?.commandId == CommandResolution.id(p))
    fun compacted(): CommandTerminalProof {
        require(valid() && command != null && marker == null)
        val outcome = resolution?.outcome ?: when {
            acknowledgment?.draftDecision?.status in setOf("draft-conflict","draft-source-conflict") || acknowledgment?.exerciseDecision?.status == "exercise-conflict" || acknowledgment?.noteDecision?.status == "note-conflict" || acknowledgment?.status == "profile-conflict" || acknowledgment?.revocationDecision?.status == "revocation-conflict" -> "not-applied-conflict"
            else -> "applied"
        }
        return CommandTerminalProof(source="compacted-v1", marker=SettledCommandMarker(uid=command.uid,
            commandKind=CommandResolution.kind(command), commandId=CommandResolution.id(command),
            requestHashVersion=CommandResolution.version(command),requestHash=CommandResolution.hash(command),
            source=source,outcome=outcome,decidedAtMillis=resolution?.decidedAtMillis)).also { require(it.valid()) }
    }
    fun retired(): CommandTerminalProof = if (command?.action == "save-program-draft" || command?.action == "save-trainer-note" || command?.action?.let(CustomExerciseProtocol::action)==true) compacted() else this
    fun valid(): Boolean = runCatching {
        if (source == "compacted-v1") return@runCatching command == null && resolution == null && acknowledgment == null && marker?.valid() == true
        require(marker == null)
        val command = command ?: return@runCatching false
        require(validPendingCommand(command.uid,command))
        when(source) {
            "resolution-v1" -> acknowledgment == null && resolution?.let { CommandResolution.valid(command,it) } == true
            "normal-v1" -> resolution == null && acknowledgment != null && (acknowledgment.draftDecision != null || acknowledgment.replayed != null || acknowledgment.noteDecision != null || acknowledgment.exerciseDecision != null) && acknowledgment.deliveryToken == null &&
                InvitationJson.validReply(CommandResolution.original(command), if(command.action=="save-program-draft" || command.action=="save-trainer-note" || CustomExerciseProtocol.action(command.action)) acknowledgment else acknowledgment.copy(replayed=true)) &&
                (command.action != "create" || acknowledgment.invitationId == CommandResolution.id(command))
            else -> false
        }
    }.getOrDefault(false)
}

/** Only produced locally after a full terminal proof was validated and durably written. */
@Serializable
data class SettledCommandMarker(
    @kotlinx.serialization.Required val schemaVersion: Int = 1,
    val uid: String, val commandKind: String, val commandId: String,
    val requestHashVersion: String, val requestHash: String, val source: String,
    val outcome: String, val decidedAtMillis: Long? = null,
) {
    override fun toString() = "SettledCommandMarker(redacted)"
    fun valid(): Boolean {
        val version = when(commandKind) {
            "invitation-create", "invitation-resend", "invitation-revoke", "invitation-accept" -> "invitation-v1"
            "onboarding-complete" -> "onboarding-v1"
            "profile-update" -> "profile-v1"
            "client-membership-revoke" -> "client-revocation-v1"
            "program-draft-save" -> "program-draft-v1"
            "trainer-note-save" -> "trainer-note-v1"
            "custom-exercise-create", "custom-exercise-correct" -> "custom-exercise-v1"
            else -> return false
        }
        return schemaVersion == 1 && com.delminiusapps.tillfailure.persistence.RecoveryUidContract.isValid(uid) &&
            requestHashVersion == version && listOf(commandId,requestHash).all { Regex("[a-f0-9]{64}").matches(it) } &&
            outcome in setOf("applied","not-applied-conflict","not-applied-fenced") &&
            (outcome != "not-applied-conflict" || commandKind in setOf("program-draft-save","profile-update","client-membership-revoke","trainer-note-save","custom-exercise-create","custom-exercise-correct")) &&
            when(source) {
                "resolution-v1" -> decidedAtMillis != null && decidedAtMillis in 0..8_640_000_000_000_000L
                "normal-v1" -> decidedAtMillis == null && outcome != "not-applied-fenced"
                else -> false
            }
    }
}
