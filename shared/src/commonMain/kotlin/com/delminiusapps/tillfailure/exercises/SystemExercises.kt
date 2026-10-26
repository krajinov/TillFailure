package com.delminiusapps.tillfailure.exercises

import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.identity.isPathSafeAuthUid
import kotlinx.serialization.json.*

/** Transient ownership only; it is never a server credential or a durable cache key. */
data class ExerciseScope(val uid: String, val workspaceId: String, val epoch: Long) {
    init { require(isPathSafeAuthUid(uid) && isPathSafeAuthUid(workspaceId)) }
}
data class ExerciseVisit(val scope: ExerciseScope, val visit: Long, val exerciseId: String? = null)
data class SystemExercise(val id: String, val name: String, val nameNormalized: String,
    val muscleGroups: List<String>, val equipment: List<String>, val instructions: String)
data class ExerciseResult(val items: List<SystemExercise> = emptyList(), val nextId: String? = null,
    val detail: SystemExercise? = null, val error: ExerciseError? = null)
enum class ExerciseError { Unavailable, Malformed, Denied, Missing }
interface SystemExerciseRepository {
    /** Fixed published query (20 + one lookahead), or a fresh exact point read. Never cache fallback. */
    fun read(uid: String, afterId: String?, exerciseId: String?, callback: (ExerciseResult) -> Unit): FirebaseCancellation
}

/** Validates every returned document, including lookahead, before publishing any page. */
object SystemExerciseProtocol {
    const val PAGE_SIZE = 20
    const val MAX_RESPONSE_BYTES = 256 * 1024
    fun validId(id: String) = id.length in 1..128 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
    fun validRequest(uid: String, afterId: String?, exerciseId: String?) = isPathSafeAuthUid(uid) &&
        (afterId == null || validId(afterId)) && (exerciseId == null || validId(exerciseId)) && (afterId == null || exerciseId == null)
    fun result(afterId: String?, exerciseId: String?, body: String?, error: String?): ExerciseResult {
        if (error != null) return ExerciseResult(error = when (error) { "PERMISSION_DENIED", "UNAUTHENTICATED" -> ExerciseError.Denied; "MALFORMED" -> ExerciseError.Malformed; else -> ExerciseError.Unavailable })
        return runCatching {
            require(body != null && body.encodeToByteArray().size <= MAX_RESPONSE_BYTES)
            val envelope = Json.parseToJsonElement(body).jsonObject
            require(envelope.keys == setOf("documents"))
            val docs = envelope.getValue("documents").jsonArray
            require(docs.size <= if (exerciseId == null) PAGE_SIZE + 1 else 1)
            val items = docs.map { doc ->
                val wrapper = doc.jsonObject
                require(wrapper.keys == setOf("id", "data"))
                val id = string(wrapper.getValue("id")); require(validId(id))
                val data = wrapper.getValue("data").jsonObject
                require(data.keys == setOf("schemaVersion", "name", "nameNormalized", "muscleGroups", "equipment", "instructions", "status"))
                require(data.getValue("schemaVersion").jsonPrimitive.let { !it.isString && it.content == "1" })
                require(string(data.getValue("status")) == "published")
                val name = text(data.getValue("name"), 120)
                val normalized = text(data.getValue("nameNormalized"), 120)
                val instructions = text(data.getValue("instructions"), 2000, multiline = true)
                require(instructions.encodeToByteArray().size <= 8000)
                SystemExercise(id, name, normalized, labels(data.getValue("muscleGroups")), labels(data.getValue("equipment")), instructions)
            }
            require(items.zipWithNext().all { (a, b) -> a.id < b.id })
            require(afterId == null || items.all { it.id > afterId })
            if (exerciseId != null) {
                require(items.isEmpty() || items.single().id == exerciseId)
                if (items.isEmpty()) ExerciseResult(error = ExerciseError.Missing) else ExerciseResult(detail = items.single())
            } else ExerciseResult(items = items.take(PAGE_SIZE), nextId = if (items.size > PAGE_SIZE) items[PAGE_SIZE - 1].id else null)
        }.getOrDefault(ExerciseResult(error = ExerciseError.Malformed))
    }
    private fun string(value: JsonElement): String = value.jsonPrimitive.let { require(it.isString); it.content }
    private fun labels(value: JsonElement): List<String> = value.jsonArray.let { array ->
        require(array.size <= 8); array.map { text(it, 40) }.also { require(it.distinct().size == it.size) }
    }
    private fun text(value: JsonElement, maxScalars: Int, multiline: Boolean = false): String {
        val text = string(value)
        require(text.isNotBlank() && text.trim() == text && '\r' !in text)
        var i = 0; var scalars = 0
        while (i < text.length) {
            val c = text[i++]
            require(!c.isLowSurrogate())
            if (c.isHighSurrogate()) require(i < text.length && text[i++].isLowSurrogate())
            require(!c.isISOControl() || multiline && c == '\n')
            scalars++
        }
        require(scalars <= maxScalars)
        return text
    }
}
