package com.delminiusapps.tillfailure.clients

import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.identity.isPathSafeAuthUid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Memory only; neither cursors nor names are saved in destination state or recovery storage. */
data class ClientScope(val uid: String, val workspaceId: String, val trainerId: String, val authorityEpoch: Long) {
    init { require(isPathSafeAuthUid(uid) && uid == trainerId && isPathSafeAuthUid(workspaceId) && '.' !in workspaceId) }
}
@Serializable
data class ClientRecord(val clientId: String, val workspaceId: String, val displayName: String?, val relationshipStatus: String, val onboardingStatus: String) {
    override fun toString() = "ClientRecord(redacted)"
}
@Serializable
data class ClientPage(val items: List<ClientRecord>, val nextCursor: String?) {
    override fun toString() = "ClientPage(redacted)"
}
@Serializable
class ClientRequest(val action: String, val workspaceId: String, val pageSize: Int? = null, val cursor: String? = null, val clientId: String? = null) {
    override fun toString() = "ClientRequest(action=$action)"
}
data class ClientResult(val page: ClientPage? = null, val detail: ClientRecord? = null, val error: String? = null)
interface ClientRepository {
    val commands: com.delminiusapps.tillfailure.invitations.InvitationClient? get() = null
    fun read(scope: ClientScope, request: ClientRequest, callback: (ClientResult) -> Unit): FirebaseCancellation
}

object ClientJson {
    private val json = Json { explicitNulls = true }
    fun request(r: ClientRequest): String = buildJsonObject {
        put("action", r.action); put("workspaceId", r.workspaceId)
        if (r.action == "list") { put("pageSize", r.pageSize); put("cursor", r.cursor) }
        else put("clientId", r.clientId)
    }.toString()
    fun valid(r: ClientRequest) = isPathSafeAuthUid(r.workspaceId) && '.' !in r.workspaceId && when (r.action) {
        "list" -> r.pageSize in 1..20 && r.clientId == null && (r.cursor == null || cursor(r.cursor))
        "get" -> r.clientId?.let(::isPathSafeAuthUid) == true && r.pageSize == null && r.cursor == null
        else -> false
    }
    private fun cursor(v: String) = v.length == 5522 && v.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }
    fun safeError(e: String?) = when (e) { "unauthorized", "unauthenticated" -> "access-lost"; "invalid-cursor" -> "invalid-cursor"; else -> "unavailable" }
    fun result(r: ClientRequest, body: String?, error: String?): ClientResult {
        if (error != null) return ClientResult(error = safeError(error))
        return runCatching {
            require(valid(r) && body != null && body.length <= 64 * 1024)
            fun validate(item: ClientRecord) {
                require(isPathSafeAuthUid(item.clientId) && item.workspaceId == r.workspaceId && item.relationshipStatus == "active" && item.onboardingStatus in setOf("pending", "complete"))
                val name = item.displayName
                require(if (name == null) item.onboardingStatus == "pending" else name.length in 1..80 && name.trim() == name && name.none { it.isISOControl() } && validNameUtf16(name))
            }
            if (r.action == "get") {
                val detail = json.decodeFromString<ClientRecord>(body); validate(detail); require(detail.clientId == r.clientId)
                ClientResult(detail = detail)
            } else {
                val page = json.decodeFromString<ClientPage>(body)
                require(page.items.size <= r.pageSize!! && page.items.map { it.clientId }.distinct().size == page.items.size)
                page.items.forEach(::validate)
                require(page.nextCursor == null || page.items.size == r.pageSize && cursor(page.nextCursor))
                ClientResult(page = page)
            }
        }.getOrDefault(ClientResult(error = "unavailable"))
    }
    private fun validNameUtf16(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c.isLowSurrogate()) return false
            if (c.isHighSurrogate() && (i >= s.length || !s[i++].isLowSurrogate())) return false
        }
        return true
    }
    fun callable(r: ClientRequest, status: Int, body: String): ClientResult = runCatching {
        require(body.length <= 64 * 1024)
        val envelope = json.parseToJsonElement(body).jsonObject
        if (status == 200 && envelope.keys == setOf("result")) result(r, envelope.getValue("result").toString(), null)
        else {
            require(envelope.keys == setOf("error"))
            val error = envelope.getValue("error").jsonObject
            require(error.keys.all { it in setOf("status", "message", "details") })
            val code = error["status"]?.jsonPrimitive?.content
            val expected = mapOf("UNAUTHENTICATED" to 401, "PERMISSION_DENIED" to 403, "FAILED_PRECONDITION" to 400, "INVALID_ARGUMENT" to 400, "UNAVAILABLE" to 503)
            require(expected[code] == status)
            result(r, null, error["message"]?.jsonPrimitive?.content ?: "unavailable")
        }
    }.getOrDefault(ClientResult(error = "unavailable"))
}
