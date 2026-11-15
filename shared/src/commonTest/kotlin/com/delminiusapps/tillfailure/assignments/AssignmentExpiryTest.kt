package com.delminiusapps.tillfailure.assignments

import com.delminiusapps.tillfailure.exercises.ExerciseScope
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.programs.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class AssignmentExpiryTest {
    private val base=1800000000000L
    private val aid="00000000-0000-4000-8000-000000000003"
    private val pid="00000000-0000-4000-8000-000000000004"
    private val scope=AssignmentScope("A","w","B","trainer",1)
    private inline fun <reified T> obj(value:T)=AssignmentContract.json.encodeToJsonElement(value).jsonObject
    private inner class Fixture(lifetime:Long=10000) {
        val c=ProgramPublicationTest().content();val w=c.workouts.single();val i=w.items.single()
        val hash=AssignmentContract.copyHash(AssignmentCopy(1,c.title,c.description,c.workouts))
        val header=AssignmentHeader(1,"w","B",aid,"content",c.templateId,"v0000000001",1,PublicationProtocol.contentHash(c),"A",hash,1,"ready","active",base+lifetime-AssignmentContract.DAY_MS,base+lifetime,"UTC")
        val paths=listOf("snapshots/content","snapshots/content/workouts/${w.id}","snapshots/content/workouts/${w.id}/exercises/${i.id}","plannedWorkouts/$pid").sorted()
        val inventory=AssignmentInventory(1,"w","B",aid,"content",1,hash,paths)
        val docs=mapOf("" to obj(header),"manifests/download" to obj(inventory),
            "snapshots/content" to obj(AssignmentSnapshot(1,"w","B",aid,"content",c.templateId,"v0000000001",1,header.sourceContentHash,hash,1,c.title,c.description,listOf(w.id))),
            "snapshots/content/workouts/${w.id}" to obj(AssignmentWorkoutDocument(1,"w","B",aid,"content",w.id,w.title,listOf(i.id))),
            "snapshots/content/workouts/${w.id}/exercises/${i.id}" to obj(AssignmentItemDocument(1,"w","B",aid,"content",w.id,i.id,i.sourceKind,i.sourceId,i.sourceRevision,i.sourceHash,i.name,i.instructions,i.muscleGroups,i.equipment,i.prescription)),
            "plannedWorkouts/$pid" to obj(AssignmentPlanDocument(1,"w","B",aid,"content",pid,w.id,header.scheduledInstantMillis,"UTC","planned",1)))
        init {assertTrue(AssignmentClientDocuments.valid("B","w",aid,docs.mapKeys{(p,_)->"users/B/workspaces/w/assignedPrograms/$aid"+if(p.isEmpty())"" else "/$p"}))}
    }
    private class Wall(var millis:Long):Clock {override fun now()=Instant.fromEpochMilliseconds(millis)}
    private data class Call(val scope:ExerciseScope,val request:AssignmentReadRequest,val callback:(AssignmentReadResult)->Unit)
    private inner class Repository:AssignmentRepository {
        var fixture=Fixture()
        override val commands=object:InvitationClient {
            override val memory=InvitationMemory()
            override fun pending(uid:String):PendingInvitationCommand?=null
            override fun newCommandKey()=error("Read-only test")
            override fun execute(uid:String,request:InvitationRequest,callback:(InvitationOutcome)->Unit):FirebaseCancellation=error("No command permitted")
        }
        val calls=mutableListOf<Call>();var cancellations=0
        override fun read(scope:ExerciseScope,request:AssignmentReadRequest,callback:(AssignmentReadResult)->Unit):FirebaseCancellation {
            calls+=Call(scope,request,callback);return FirebaseCancellation{cancellations++}
        }
        fun packet(call:Call,time:JsonElement?=JsonPrimitive(base))=buildJsonObject {
            val r=call.request;put("workspaceId","w");put("clientUid","B")
            when(r.action) {
                "assignment-list","client-assignment-list"->{put("items",JsonArray(listOf(obj(fixture.header))));put(if(r.action.startsWith("client-"))"afterId" else "after",JsonNull)}
                "assignment-get"->{put("header",obj(fixture.header));put("inventory",obj(fixture.inventory));time?.let{put("serverNowMillis",it)}}
                "client-assignment-time"->{put("assignmentId",aid);put("snapshotHash",fixture.hash);put("accessExpiresAtMillis",fixture.header.accessExpiresAtMillis);time?.let{put("serverNowMillis",it)}}
                else->{put("assignmentId",aid);put("relativePath",r.relativePath!!);put("document",fixture.docs.getValue(r.relativePath));if(r.action=="assignment-document")time?.let{put("serverNowMillis",it)}}
            }
        }
        fun result(call:Call,time:JsonElement?=JsonPrimitive(base))=AssignmentReads.result(call.scope,call.request,packet(call,time).toString(),null)
        fun reply(call:Call,time:JsonElement?=JsonPrimitive(base))=call.callback(result(call,time))
    }
    private inner class Visit(scheduler:TestCoroutineScheduler,offset:Long=0) {
        val repository=Repository();val wall=Wall(base+offset)
        var nanosOffset=0L
        val monotonic=object:AbstractLongTimeSource(DurationUnit.NANOSECONDS){override fun read()=scheduler.currentTime*1000000+nanosOffset}
        val model=AssignmentViewModel(repository,null,wall,monotonic)
        fun open(owner:AssignmentScope=scope):Call {
            model.attach(owner);repository.reply(repository.calls.last());model.onEvent(AssignmentEvent.Open(aid))
            while(true) {val call=repository.calls.last();if(call.request.action=="client-assignment-time" || call.request.action=="assignment-document" && call.request.relativePath=="")return call;repository.reply(call)}
        }
    }
    @AfterTest fun cleanup(){Dispatchers.resetMain()}
    @Test fun wallClockAheadBehindAndChangesWhileOpenDoNotAffectLifetime()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for(offset in listOf(-7*AssignmentContract.DAY_MS,7*AssignmentContract.DAY_MS)) {
            val v=Visit(testScheduler,offset);v.repository.reply(v.open());runCurrent();assertNotNull(v.model.state.value.content)
            v.wall.millis+=100*AssignmentContract.DAY_MS;advanceTimeBy(4000);runCurrent();assertNotNull(v.model.state.value.content)
            v.wall.millis-=200*AssignmentContract.DAY_MS;advanceTimeBy(6000);runCurrent();assertNull(v.model.state.value.content);assertNull(v.model.state.value.detail);assertNull(v.model.state.value.plan)
            v.model.clear()
        }
    }
    @Test fun finalHeaderLatencyIsDeductedAndExpiryInvalidatesDuplicateCallbacks()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val v=Visit(testScheduler);val final=v.open()
        advanceTimeBy(3000);v.repository.reply(final);runCurrent();assertNotNull(v.model.state.value.content)
        advanceTimeBy(6999);runCurrent();assertNotNull(v.model.state.value.content)
        advanceTimeBy(1);runCurrent();assertNull(v.model.state.value.content)
        v.repository.reply(final);assertNull(v.model.state.value.content);assertNotNull(v.model.state.value.error);v.model.clear()
    }
    @Test fun expiryDuringFinalRequestNeverDisplaysAndCannotBeRevived()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val v=Visit(testScheduler);val final=v.open()
        advanceTimeBy(10001);v.repository.reply(final);runCurrent();assertNull(v.model.state.value.content);assertNotNull(v.model.state.value.error)
        v.repository.reply(final);assertNull(v.model.state.value.content);v.model.clear()
    }
    @Test fun fractionalRequestLatencyRoundsUpConservatively()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val v=Visit(testScheduler);val final=v.open()
        v.nanosOffset=500000;v.repository.reply(final);runCurrent();assertNotNull(v.model.state.value.content)
        advanceTimeBy(9999);runCurrent();assertNull(v.model.state.value.content);v.model.clear()
    }
    @Test fun clientRetainsItsOwnBoundAndCopiedDocumentProtocol()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val v=Visit(testScheduler,50*AssignmentContract.DAY_MS)
        val time=v.open(AssignmentScope("B","w","B","client",1));assertEquals("client-assignment-time",time.request.action)
        advanceTimeBy(1000);v.repository.reply(time);runCurrent();assertNotNull(v.model.state.value.content)
        v.wall.millis-=100*AssignmentContract.DAY_MS;advanceTimeBy(9000);runCurrent();assertNull(v.model.state.value.content);v.model.clear()
    }
    @Test fun missingMalformedAndExpiredBoundsFailClosedInBothTrainerPackets()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val r=Repository()
        val bounds=listOf(null,JsonNull,JsonPrimitive("1800000000000"),JsonPrimitive(true),JsonPrimitive(-1),JsonPrimitive(1.5),JsonPrimitive(AssignmentContract.MAX_MILLIS+1),JsonPrimitive(base+10000))
        for(action in listOf("assignment-get","assignment-document"))for(bound in bounds) {
            val request=AssignmentReadRequest(action,"w","B",assignmentId=aid,relativePath=if(action=="assignment-document")"" else null)
            val call=Call(scope.read(),request){};assertNotNull(r.result(call,bound).error,"$action accepted $bound")
        }
        val v=Visit(testScheduler);v.repository.reply(v.open(),null);assertNull(v.model.state.value.content);assertFalse(v.model.state.value.busy);v.model.clear()
    }
    @Test fun backgroundOrDisposalInvalidatesReadsAndFreshReentryRereadsEverything()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val v=Visit(testScheduler);val stale=v.open();v.model.clear()
        v.repository.reply(stale);assertNull(v.model.state.value.scope);assertNull(v.model.state.value.content)
        val before=v.repository.calls.size;val fresh=v.open();assertTrue(v.repository.calls.size>before+4)
        v.repository.reply(stale);assertNull(v.model.state.value.content);v.repository.reply(fresh);runCurrent();assertNotNull(v.model.state.value.content)
        v.model.clear();advanceTimeBy(20000);runCurrent();assertNull(v.model.state.value.scope);assertTrue(v.repository.cancellations>0)
    }
    @Test fun authorityLossCancelsTimerAndStaleSuccessCannotRestoreContent()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val v=Visit(testScheduler);val final=v.open();v.repository.reply(final);runCurrent()
        v.model.onEvent(AssignmentEvent.Refresh);val refresh=v.repository.calls.last();refresh.callback(AssignmentReadResult(error="unauthorized"))
        v.repository.reply(final);v.repository.reply(refresh);advanceTimeBy(20000);runCurrent();assertNull(v.model.state.value.content);assertNotNull(v.model.state.value.error);v.model.clear()
    }
    @Test fun oldTimerAndOldAuthorityEpochCannotClearOrReviveNewVisit()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val v=Visit(testScheduler);val old=v.open();v.repository.reply(old);runCurrent();advanceTimeBy(1000)
        v.repository.fixture=Fixture(20000);val fresh=v.open(scope.copy(epoch=2));v.repository.reply(fresh,JsonPrimitive(base+1000));runCurrent();assertNotNull(v.model.state.value.content)
        v.repository.reply(old);advanceTimeBy(10000);runCurrent();assertNotNull(v.model.state.value.content);assertEquals(2L,v.model.state.value.scope?.epoch)
        advanceTimeBy(9000);runCurrent();assertNull(v.model.state.value.content);v.model.clear()
    }
}
