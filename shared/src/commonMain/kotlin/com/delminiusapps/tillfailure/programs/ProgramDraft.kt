package com.delminiusapps.tillfailure.programs

import com.delminiusapps.tillfailure.exercises.CustomExerciseProtocol
import com.delminiusapps.tillfailure.exercises.ExerciseAuthority
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Serializable
data class DraftPrescription(val sets: Int?, val reps: Int?, val restSeconds: Int?, val loadGrams: Int?) {
    fun valid() = (sets == null || sets in 1..20) && (reps == null || reps in 1..1000) &&
        (restSeconds == null || restSeconds in 0..3600) && (loadGrams == null || loadGrams in 0..1_000_000)
}

@Serializable
data class DraftItem(val id: String, val sourceKind: String, val sourceId: String, val sourceRevision: Int?,
    val sourceHash: String, val name: String, val prescription: DraftPrescription) {
    fun valid() = CustomExerciseProtocol.validId(id) && when (sourceKind) {
        "system" -> Regex("[A-Za-z0-9_-]{1,128}").matches(sourceId) && sourceRevision == null
        "custom" -> CustomExerciseProtocol.validId(sourceId) && sourceRevision != null && sourceRevision in 1..Int.MAX_VALUE-1
        else -> false
    } && Regex("[a-f0-9]{64}").matches(sourceHash) && CustomExerciseProtocol.text(name,120) && prescription.valid()
    override fun toString() = "DraftItem(redacted)"
}

@Serializable
data class DraftWorkout(val id: String, val title: String, val items: List<DraftItem>) {
    override fun toString() = "DraftWorkout(redacted)"
}

@Serializable
data class DraftSave(val templateId: String, val expectedRevision: Int, val authority: ExerciseAuthority,
    val title: String, val description: String, val workouts: List<DraftWorkout>) {
    fun valid(): Boolean {
        fun text(value: String, max: Int, multiline: Boolean = false) =
            value.isEmpty() || CustomExerciseProtocol.text(value, max, multiline)
        if (!CustomExerciseProtocol.validId(templateId) || expectedRevision !in 0..Int.MAX_VALUE-2 || !authority.valid() ||
            !text(title,80) || !text(description,240,true) || workouts.size > 3 ||
            workouts.any { !CustomExerciseProtocol.validId(it.id) || !text(it.title,40) || it.items.size > 12 } ||
            workouts.map { it.id }.distinct().size != workouts.size) return false
        val items = workouts.flatMap { it.items }
        if (items.size > 12 || items.any { !it.valid() } || items.map { it.id }.distinct().size != items.size) return false
        val strings = listOf(title, description) + workouts.map { it.title } + items.map { it.name }
        // Count Unicode scalars, not UTF-16 code units. Individual validators reject unpaired surrogates.
        val scalars = strings.sumOf { s -> s.count { !it.isLowSurrogate() } }
        return scalars <= 650 && strings.sumOf { it.encodeToByteArray().size } <= 2600
    }
    override fun toString() = "DraftSave(redacted)"
}

/** Exact explicit-null wire contract shared by production issuance and feasibility checks. */
object DraftSerialization {
    private val wire = Json { explicitNulls = true }
    fun coveredKey(key: String) = Regex("or1-b91f0176ec74475e8cf7406e25b7e9d3-[A-Za-z0-9_-]{1,80}").matches(key)
    fun element(draft: DraftSave) = wire.encodeToJsonElement(draft)
    fun descriptor(workspaceId: String, key: String, draft: DraftSave): JsonObject = buildJsonObject {
        require(CustomExerciseProtocol.workspace(workspaceId) && coveredKey(key) && draft.valid())
        put("action", "save-program-draft")
        put("workspaceId", workspaceId)
        put("idempotencyKey", key)
        put("draftSave", wire.encodeToJsonElement(draft))
    }
    fun resolver(workspaceId: String, key: String, draft: DraftSave): String = buildJsonObject {
        put("protocolVersion", 1)
        put("descriptor", descriptor(workspaceId, key, draft))
    }.toString()
}
