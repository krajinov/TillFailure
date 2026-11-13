package com.delminiusapps.tillfailure.assignments

import com.delminiusapps.tillfailure.exercises.CustomExerciseProtocol
import com.delminiusapps.tillfailure.identity.isPathSafeAuthUid
import com.delminiusapps.tillfailure.programs.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

@Serializable data class AssignmentHeader(val schemaVersion:Int,val workspaceId:String,val clientId:String,val assignmentId:String,val snapshotId:String,
    val sourceTemplateId:String,val sourceVersionId:String,val sourceVersionNumber:Int,val sourceContentHash:String,val trainerId:String,val snapshotHash:String,
    val revision:Int,val lifecycleState:String,val accessStatus:String,val scheduledInstantMillis:Long,val accessExpiresAtMillis:Long,val timeZoneId:String)
@Serializable data class AssignmentSnapshot(val schemaVersion:Int,val workspaceId:String,val clientId:String,val assignmentId:String,val snapshotId:String,
    val sourceTemplateId:String,val sourceVersionId:String,val sourceVersionNumber:Int,val sourceContentHash:String,val contentHash:String,val copyPolicyVersion:Int,
    val title:String,val description:String,val workoutIds:List<String>)
@Serializable data class AssignmentWorkoutDocument(val schemaVersion:Int,val workspaceId:String,val clientId:String,val assignmentId:String,val snapshotId:String,
    val workoutId:String,val title:String,val itemIds:List<String>)
@Serializable data class AssignmentItemDocument(val schemaVersion:Int,val workspaceId:String,val clientId:String,val assignmentId:String,val snapshotId:String,
    val workoutId:String,val itemId:String,val sourceKind:String,val sourceId:String,val sourceRevision:Int?,val sourceHash:String,val name:String,
    val instructions:String,val muscleGroups:List<String>,val equipment:List<String>,val prescription:DraftPrescription) {
    fun item()=PublicationItem(itemId,sourceKind,sourceId,sourceRevision,sourceHash,name,prescription,instructions,muscleGroups,equipment)
}
@Serializable data class AssignmentPlanDocument(val schemaVersion:Int,val workspaceId:String,val clientId:String,val assignmentId:String,val snapshotId:String,
    val plannedWorkoutId:String,val workoutId:String,val scheduledInstantMillis:Long,val timeZoneId:String,val status:String,val revision:Int)
@Serializable data class AssignmentInventory(val schemaVersion:Int,val workspaceId:String,val clientId:String,val assignmentId:String,val snapshotId:String,
    val assignmentRevision:Int,val snapshotHash:String,val requiredPaths:List<String>)

/** No source fetch or source DTO is an input to this client validation boundary. */
object AssignmentClientDocuments {
    private fun <T> decode(serializer:KSerializer<T>,value:JsonObject):T {
        val decoded=AssignmentContract.json.decodeFromJsonElement(serializer,value)
        require(AssignmentContract.json.encodeToJsonElement(serializer,decoded)==value)
        return decoded
    }
    fun valid(client:String,wid:String,aid:String,documents:Map<String,JsonObject>):Boolean=runCatching {
        require(isPathSafeAuthUid(client) && CustomExerciseProtocol.workspace(wid) &&
            CustomExerciseProtocol.validId(aid) && documents.size in 6..17)
        val base="users/$client/workspaces/$wid/assignedPrograms/$aid"
        fun value(path:String)=documents.getValue(path)
        fun tuple(o:JsonObject)=o["schemaVersion"]==JsonPrimitive(1) && o["workspaceId"]==JsonPrimitive(wid) && o["clientId"]==JsonPrimitive(client) &&
            o["assignmentId"]==JsonPrimitive(aid) && o["snapshotId"]==JsonPrimitive("content")
        val h=decode(AssignmentHeader.serializer(),value(base))
        require(tuple(value(base)) && isPathSafeAuthUid(h.trainerId) && h.revision==1 && h.lifecycleState=="ready" && h.accessStatus=="active" && h.timeZoneId=="UTC" &&
            AssignmentContract.millis(h.scheduledInstantMillis) && AssignmentContract.millis(h.accessExpiresAtMillis) && h.accessExpiresAtMillis-h.scheduledInstantMillis==AssignmentContract.DAY_MS &&
            h.sourceVersionNumber in 1..2147483645 && h.sourceVersionId==PublicationProtocol.versionId(h.sourceVersionNumber))
        val snapshotPath="$base/snapshots/content";val snapshot=decode(AssignmentSnapshot.serializer(),value(snapshotPath))
        require(tuple(value(snapshotPath)) && snapshot.copyPolicyVersion==1 && snapshot.workoutIds.size==1 &&
            snapshot.sourceTemplateId==h.sourceTemplateId && snapshot.sourceVersionId==h.sourceVersionId && snapshot.sourceVersionNumber==h.sourceVersionNumber && snapshot.sourceContentHash==h.sourceContentHash)
        val workoutId=snapshot.workoutIds.single();require(CustomExerciseProtocol.validId(workoutId))
        val workoutPath="$snapshotPath/workouts/$workoutId";val workout=decode(AssignmentWorkoutDocument.serializer(),value(workoutPath))
        require(tuple(value(workoutPath)) && workout.workoutId==workoutId && workout.itemIds.size in 1..12 && workout.itemIds.distinct()==workout.itemIds)
        val items=workout.itemIds.map { id->
            require(CustomExerciseProtocol.validId(id))
            val path="$workoutPath/exercises/$id";val item=decode(AssignmentItemDocument.serializer(),value(path))
            require(tuple(value(path)) && item.workoutId==workoutId && item.itemId==id)
            item.item()
        }
        val copied=AssignmentCopy(1,snapshot.title,snapshot.description,listOf(PublicationWorkout(workoutId,workout.title,items)))
        val content=PublicationContent(1,wid,h.sourceTemplateId,h.trainerId,copied.title,copied.description,copied.workouts)
        require(content.valid(h.trainerId,wid) && AssignmentContract.copyHash(copied)==h.snapshotHash && snapshot.contentHash==h.snapshotHash && PublicationProtocol.contentHash(content)==h.sourceContentHash)
        val plans=documents.filterKeys{it.startsWith("$base/plannedWorkouts/")};require(plans.size==1)
        val (planPath,rawPlan)=plans.entries.single();val plan=decode(AssignmentPlanDocument.serializer(),rawPlan)
        require(tuple(rawPlan) && CustomExerciseProtocol.validId(plan.plannedWorkoutId) && planPath=="$base/plannedWorkouts/${plan.plannedWorkoutId}" &&
            plan.workoutId==workoutId && plan.scheduledInstantMillis==h.scheduledInstantMillis && plan.timeZoneId=="UTC" && plan.status=="planned" && plan.revision==1)
        val manifestPath="$base/manifests/download";val inventory=decode(AssignmentInventory.serializer(),value(manifestPath))
        val required=(listOf("snapshots/content","snapshots/content/workouts/$workoutId",planPath.removePrefix("$base/"))+workout.itemIds.map{"snapshots/content/workouts/$workoutId/exercises/$it"}).sorted()
        require(tuple(value(manifestPath)) && inventory.assignmentRevision==1 && inventory.snapshotHash==h.snapshotHash && inventory.requiredPaths==required)
        documents.keys==((required.map{"$base/$it"})+base+manifestPath).toSet()
    }.getOrDefault(false)
}
