package com.delminiusapps.tillfailure.programs

import com.delminiusapps.tillfailure.exercises.*
import com.delminiusapps.tillfailure.invitations.canonical
import com.delminiusapps.tillfailure.invitations.commandDigest
import kotlinx.serialization.json.*

object DraftSources {
    fun system(itemId:String,e:SystemExercise):DraftItem {
        val definition=buildJsonObject {
            put("schemaVersion",1);put("name",e.name);put("nameNormalized",e.nameNormalized)
            put("muscleGroups",JsonArray(e.muscleGroups.map(::JsonPrimitive)));put("equipment",JsonArray(e.equipment.map(::JsonPrimitive)))
            put("instructions",e.instructions);put("status","published")
        }
        val hash=commandDigest(canonical(buildJsonObject {put("sourceKind","system");put("sourceId",e.id);put("definition",definition)}))
        return DraftItem(itemId,"system",e.id,null,hash,e.name,DraftPrescription(null,null,null,null))
    }
    fun custom(itemId:String,wid:String,e:CustomExercise):DraftItem {
        val definition=buildJsonObject {
            put("schemaVersion",e.schemaVersion);put("policyVersion",e.policyVersion);put("ownerUid",e.ownerUid);put("name",e.name)
            put("instructions",e.instructions);put("muscleGroups",JsonArray(e.muscleGroups.map(::JsonPrimitive)));put("equipment",JsonArray(e.equipment.map(::JsonPrimitive)));put("revision",e.revision)
        }
        val hash=commandDigest(canonical(buildJsonObject {put("sourceKind","custom");put("workspaceId",wid);put("sourceId",e.exerciseId);put("definition",definition)}))
        return DraftItem(itemId,"custom",e.exerciseId,e.revision,hash,e.name,DraftPrescription(null,null,null,null))
    }
}

data class DraftNumbers(val sets:String="",val reps:String="",val rest:String="",val kg:String="") {
    fun parse():DraftPrescription {
        fun integer(s:String):Int? {if(s.isEmpty())return null;require(Regex("[0-9]+").matches(s));return s.toInt()}
        val grams=if(kg.isEmpty())null else {
            require(Regex("[0-9]+(?:\\.[0-9]{1,3})?").matches(kg))
            val pieces=kg.split('.');val whole=pieces[0].toInt();require(whole in 0..1000)
            whole*1000+(pieces.getOrNull(1)?:"").padEnd(3,'0').toInt()
        }
        return DraftPrescription(integer(sets),integer(reps),integer(rest),grams).also {require(it.valid())}
    }
    companion object {
        fun from(p:DraftPrescription)=DraftNumbers(p.sets?.toString()?:"",p.reps?.toString()?:"",p.restSeconds?.toString()?:"",
            p.loadGrams?.let { "${it/1000}.${(it%1000).toString().padStart(3,'0')}" }?:"")
    }
    override fun toString()="DraftNumbers(redacted)"
}
