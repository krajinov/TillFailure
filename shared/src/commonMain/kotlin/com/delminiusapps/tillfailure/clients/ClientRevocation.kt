package com.delminiusapps.tillfailure.clients

import com.delminiusapps.tillfailure.identity.isPathSafeAuthUid
import com.delminiusapps.tillfailure.invitations.InvitationRequest
import com.delminiusapps.tillfailure.invitations.InvitationReply
import com.delminiusapps.tillfailure.invitations.InvitationJson
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable

@Serializable
data class ClientRevocation(val clientUid: String, val expectedTargetRevision: Int, val expectedWorkspaceRevision: Int,
    val expectedCallerMembershipRevision: Int, val expectedCallerLifecycleRevision: Int) {
    fun valid() = isPathSafeAuthUid(clientUid) && listOf(expectedTargetRevision, expectedWorkspaceRevision,
        expectedCallerMembershipRevision, expectedCallerLifecycleRevision).all { it in 1..Int.MAX_VALUE - 2 }
    override fun toString() = "ClientRevocation(redacted)"
}
@Serializable
data class ClientRevocationDecision(val workspaceId: String, val clientUid: String, val status: String, val commandKey: String,
    val targetMembershipRevision: Int, val workspaceRevision: Int, val callerMembershipRevision: Int, val callerLifecycleRevision: Int) {
    fun valid(workspace: String, binding: ClientRevocation, key: String): Boolean {
        if (!binding.valid() || workspaceId != workspace || clientUid != binding.clientUid || commandKey != key ||
            listOf(targetMembershipRevision,workspaceRevision,callerMembershipRevision,callerLifecycleRevision).any { it !in 1..Int.MAX_VALUE - 1 }) return false
        return when(status) {
            "client-revoked" -> targetMembershipRevision == binding.expectedTargetRevision + 1 && workspaceRevision == binding.expectedWorkspaceRevision + 1 &&
                callerMembershipRevision == binding.expectedCallerMembershipRevision && callerLifecycleRevision == binding.expectedCallerLifecycleRevision
            "revocation-conflict" -> targetMembershipRevision != binding.expectedTargetRevision || workspaceRevision != binding.expectedWorkspaceRevision ||
                callerMembershipRevision != binding.expectedCallerMembershipRevision || callerLifecycleRevision != binding.expectedCallerLifecycleRevision
            else -> false
        }
    }
    override fun toString() = "ClientRevocationDecision(redacted)"
}
object ClientRevocationProtocol {
    fun parseReply(request: InvitationRequest, body: String?): InvitationReply {
        require(body != null && body.length <= 8192)
        val root=InvitationJson.format.parseToJsonElement(body) as? JsonObject ?: error("revocation-invalid")
        val prepare=request.action == "prepare-client-revocation"
        require(root.keys == if (prepare) setOf("revocationPreparation") else setOf("revocationDecision","replayed"))
        val nested=root[if(prepare) "revocationPreparation" else "revocationDecision"] as? JsonObject ?: error("revocation-invalid")
        val strings=if(prepare) setOf("clientUid") else setOf("workspaceId","clientUid","status","commandKey")
        val numbers=if(prepare) setOf("expectedTargetRevision","expectedWorkspaceRevision","expectedCallerMembershipRevision","expectedCallerLifecycleRevision")
            else setOf("targetMembershipRevision","workspaceRevision","callerMembershipRevision","callerLifecycleRevision")
        require(nested.keys == strings + numbers)
        require(strings.all { (nested[it] as? JsonPrimitive)?.isString == true })
        require(numbers.all { (nested[it] as? JsonPrimitive)?.let { n -> !n.isString && n.longOrNull != null } == true })
        if(!prepare) require((root["replayed"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull != null } == true)
        return InvitationJson.format.decodeFromJsonElement<InvitationReply>(root)
    }
    fun prepare(workspace: String, clientUid: String) = InvitationRequest("prepare-client-revocation",workspaceId=workspace,clientUid=clientUid)
    fun command(workspace: String, binding: ClientRevocation, key: String) = InvitationRequest("revoke-client",workspaceId=workspace,idempotencyKey=key,revocation=binding)
}
