package com.delminiusapps.tillfailure.programs

import com.delminiusapps.tillfailure.exercises.ExerciseScope
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PublicationResponseFixtureTest {
    @Test fun parsesActualTypeScriptCallableContentAndRejectsTampering() {
        val root=Path.of("/private/tmp/tillfailure-m6p4-response-fixtures")
        // Opt in via the actual backend fixture export; common suites remain self contained.
        if(!Files.exists(root.resolve("preview.json")))return
        val preview=String(Files.readAllBytes(root.resolve("preview.json")),Charsets.UTF_8);val o=PublicationProtocol.json.parseToJsonElement(preview).jsonObject
        val p=PublicationProtocol.json.decodeFromJsonElement<ProgramPublish>(o.getValue("publish"))
        val scope=ExerciseScope("publication_author","publication_workspace",1)
        val request=PublicationReadRequest(scope.workspaceId,p.templateId,"publication-prepare",p.expectedRevision,p.draftHash)
        val result=PublicationReads.result(scope,request,preview,null);assertNull(result.error);assertNotNull(result.preview)
        val version=String(Files.readAllBytes(root.resolve("version.json")),Charsets.UTF_8);val v=PublicationProtocol.json.parseToJsonElement(version).jsonObject.getValue("version").jsonObject
        val versionRequest=PublicationReadRequest(scope.workspaceId,p.templateId,"version-get",versionId=v.getValue("versionId").jsonPrimitive.content)
        assertNull(PublicationReads.result(scope,versionRequest,version,null).error)
        assertNotNull(PublicationReads.result(scope,request,preview.replaceFirst("😀","x"),null).error)
        assertNotNull(PublicationReads.result(scope,versionRequest,version.replaceFirst("😀","x"),null).error)
        assertNull(PublicationReads.callable(scope,request,200,"{\"result\":$preview}").error)
        assertNotNull(PublicationReads.callable(scope,request,200,"{\"result\":$preview,\"extra\":true}").error)
        println("PUBLICATION_ACTUAL_NATIVE_PARSER previewBytes=${preview.encodeToByteArray().size} versionResponseBytes=${version.encodeToByteArray().size} items=${result.preview.preview.workouts.sumOf{it.items.size}}")
    }
}
