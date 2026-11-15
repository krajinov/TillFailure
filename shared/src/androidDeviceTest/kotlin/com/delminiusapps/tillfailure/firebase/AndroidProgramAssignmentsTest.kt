package com.delminiusapps.tillfailure.firebase

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.exercises.*
import com.delminiusapps.tillfailure.programs.*
import com.delminiusapps.tillfailure.assignments.*
import com.delminiusapps.tillfailure.clients.NativeClientsModels
import com.delminiusapps.tillfailure.identity.*
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.AndroidAtomicFilePersistence
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import kotlin.test.*

/** Test-source-only: original SDK commands and encrypted partitions survive actual process loss. */
class AndroidProgramAssignmentsTest {
    private val host get()=InstrumentationRegistry.getInstrumentation()
    private fun main(block:()->Unit)=host.runOnMainSync(block)
    private fun until(check:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);while(!check() && System.nanoTime()<end)Thread.sleep(50);assertTrue(check(),"Draft native state timeout")}
    private fun <T> await(start:((T)->Unit)->Unit):T {val latch=CountDownLatch(1);val result=AtomicReference<T>();start{result.set(it);latch.countDown()};assertTrue(latch.await(45,TimeUnit.SECONDS));return result.get()}
    @Test fun trainerDetailExpiresUsingServerBound() {
        val args=InstrumentationRegistry.getArguments();val prefix=requireNotNull(args.getString("m7p1bPrefix"));require(Regex("m7p1br[a-f0-9]{8}").matches(prefix))
        val password=requireNotNull(args.getString("m7p1bPassword"));val uid="${prefix}_android_trainer_expiry";val wid="${uid}_ws";val other="${uid}_client"
        val context=object:ContextWrapper(host.targetContext){override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("${uid}_$name",mode);override fun getNoBackupFilesDir()=java.io.File(super.getNoBackupFilesDir(),uid).apply{mkdirs()}}
        val client=AndroidFirebaseSpikeClient(host.targetContext,FirebaseEmulatorConfiguration(host="10.0.2.2",authPort=50799,firestorePort=50780,functionsPort=50701),AccountCallbackFence(),productMemoryCache=true,overrideProductAppName=uid,protectedContextForTest=context)
        assertNull(await<StableFirebaseFailure?>{client.signIn("$uid@example.invalid",password,0,it)})
        lateinit var root:NativeClientsModels;main{root=NativeClientsModels(client)};until{root.identity.currentState().status==GateStatus.TrainerHome}
        val request=AssignmentReadRequest("assignment-get",wid,other,assignmentId="00000000-0000-4000-8000-000000000003")
        val detail=await<AssignmentReadResult>{client.assignments.read(ExerciseScope(uid,wid,1),request,it)};assertNull(detail.error);assertNotNull(detail.serverNowMillis)
        lateinit var model:AssignmentViewModel;main{model=AssignmentViewModel(client.assignments).also{it.attach(AssignmentScope(uid,wid,other,"trainer",1))}};until{!model.state.value.busy}
        main{model.onEvent(AssignmentEvent.Open(request.assignmentId!!))};until{!model.state.value.busy}
        assertNotNull(model.state.value.content);assertNull(model.state.value.error)
        until{model.state.value.content==null};assertNull(model.state.value.detail);assertNull(model.state.value.plan);assertTrue(model.state.value.error!!.contains("expired"));main{model.clear()}
        assertNull(client.invitations.pending(uid));println("M7P1B_ANDROID_TRAINER_EXPIRY")
        CountDownLatch(1).await(180,TimeUnit.SECONDS);error("Host checkpoints before stopping this read-only probe")
    }
    @Test fun commandsAcrossProcessRestart() {
        val args=InstrumentationRegistry.getArguments();val phase=requireNotNull(args.getString("m7p1bPhase"));val prefix=requireNotNull(args.getString("m7p1bPrefix"));val password=requireNotNull(args.getString("m7p1bPassword"))
        require(Regex("m7p1br[a-f0-9]{8}").matches(prefix));val parts=phase.split('-');val kind=parts[0];val mode=parts[1];val base="${prefix}_android_${kind}_$mode";val uid=base;val wid="${uid}_ws";val other="${uid}_client"
        val context=object:ContextWrapper(host.targetContext){override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("${base}_$name",mode);override fun getNoBackupFilesDir()=java.io.File(super.getNoBackupFilesDir(),base).apply{mkdirs()}}
        val client=AndroidFirebaseSpikeClient(host.targetContext,FirebaseEmulatorConfiguration(host="10.0.2.2",authPort=48799,firestorePort=48780,functionsPort=48701),AccountCallbackFence(),productMemoryCache=true,overrideProductAppName=base,protectedContextForTest=context)
        if(!client.hasPinnedIdentity())assertNull(await<StableFirebaseFailure?>{client.signIn("$uid@example.invalid",password,0,it)});println("M7P1B_ANDROID_AUTH_READY")
        val fresh=await<FirebaseSessionRefresh>{client.refreshSession(0,it)};assertNull(fresh.failure);println("M7P1B_ANDROID_REFRESH_READY")
        var account=await<FirebaseDocumentResult>{client.getDocument("users/$uid",0,it)}
        // A cold AVD can spend the SDK's first ten-second deadline verifying gRPC classes.
        // Retry fresh SERVER reads at most twice; never accept cache or change identity/history.
        repeat(2){if(account.failure!=null){Thread.sleep(500);account=await<FirebaseDocumentResult>{client.getDocument("users/$uid",0,it)}}}
        assertNull(account.failure);println("M7P1B_ANDROID_ACCOUNT_READY")
        lateinit var root:NativeClientsModels;main{root=NativeClientsModels(client)};until{root.identity.currentState().status in setOf(GateStatus.TrainerHome,GateStatus.LockedRecovery)}
        val repository=client.assignments;val scope=ExerciseScope(uid,wid,1);val id="00000000-0000-4000-8000-000000000001"
        fun command():InvitationRequest {
            val preview=await<AssignmentReadResult>{repository.read(scope,AssignmentReadRequest("assignment-prepare",wid,other,templateId=id,versionId="v0000000001",scheduledInstantMillis=System.currentTimeMillis()+3600000),it)};assertNull(preview.error)
            val p=preview.preparation!!;val items=p.preview.workouts.single().itemIds.map { itemId->
                val result=await<AssignmentReadResult>{repository.read(scope,AssignmentReadRequest("assignment-preview-item",wid,other,assignment=p.assignment,itemId=itemId),it)};assertNull(result.error);result.item!!
            }
            p.complete(uid,items)
            return AssignmentProtocol.write(wid,if(mode=="conflict")p.assignment.copy(snapshotHash="a".repeat(64)) else p.assignment,client.invitations.newCommandKey())
        }
        if(phase.endsWith("stage")) {
            assertTrue(client.freeze(uid));assertNotNull(await<AssignmentReadResult>{repository.read(scope,AssignmentReadRequest("assignment-list",wid,other),it)}.error);client.unfreeze(uid)
            val original=command()
            val descriptor = requireNotNull(original.pending(uid))
            println("M7P1B_COMMAND ${CommandResolution.id(descriptor)} ${CommandResolution.hash(descriptor)}")
            println("M7P1B_BINDING_JSON ${CommandResolution.request(descriptor)}")
            val held=AtomicReference<()->Unit>()
            when(mode) {
                "fenced"->client.beforeInvitationTransportForTest={held.set(it)}
                "proof"->client.beforeCommandRetirementForTest={println("M7P1B_ANDROID_PROOF_DURABLE");CountDownLatch(1).await(180,TimeUnit.SECONDS);error("Host must terminate")}
                "failure"->{val field=client.javaClass.getDeclaredField("recoveryStore").apply{isAccessible=true};val store=field.get(client) as AndroidAtomicFilePersistence;var writes=0;store.beforeCommitForTest={_,_->if(++writes==2)error("Injected proof write failure")}}
                "settled"->Unit
                else->client.beforeInvitationCompletionForTest={held.set(it)}
            }
            if(mode=="admission") {
                client.beforeInvitationCompletionForTest=null
                assertTrue(client.freeze(uid));assertNotNull(await<InvitationOutcome>{client.invitations.execute(uid,original,it)}.error);assertNull(client.invitations.pending(uid));client.unfreeze(uid)
                client.beforeInvitationTransportForTest={held.set(it)}
                val callbacks=java.util.concurrent.atomic.AtomicInteger();val delivery=client.invitations.execute(uid,original){callbacks.incrementAndGet()};until{held.get()!=null}
                assertTrue(client.freeze(uid));assertNotNull(await<InvitationOutcome>{client.invitations.execute(uid,original.copy(idempotencyKey=client.invitations.newCommandKey()),it)}.error)
                assertEquals(original.pending(uid),client.invitations.pending(uid));delivery.cancel();held.get().invoke();until{client.invitations.pending(uid)==null}
                assertEquals(0,callbacks.get());client.beforeInvitationTransportForTest=null;client.unfreeze(uid);println("M7P1B_ANDROID_READY_admission")
            } else if(mode in setOf("settled","failure")) {
                val result=await<InvitationOutcome>{client.invitations.execute(uid,original,it)}
                if(mode=="failure"){assertEquals("storage-unavailable",result.error);assertEquals(original.pending(uid),client.invitations.pending(uid))}
                else {assertNull(result.error);assertNull(client.invitations.lastSettled(uid)!!.command)}
                println("M7P1B_ANDROID_READY_$mode")
            } else {
                main{client.invitations.execute(uid,original){}}
                if(mode!="proof") {until{held.get()!=null};assertEquals(original.pending(uid),client.invitations.pending(uid));main{root.identity.onEvent(IdentityEvent.SignOut)};until{root.identity.currentState().status in setOf(GateStatus.LockedRecovery,GateStatus.DepartureBlocked)}
                    assertNotNull(await<StableFirebaseFailure?>{client.signIn("$other@example.invalid",password,0,it)});println("M7P1B_ANDROID_ORIGINAL_HELD")}
            }
            CountDownLatch(1).await(180,TimeUnit.SECONDS);error("Host must terminate stage")
        }
        if(mode !in setOf("proof","settled","admission")) {
            val p=client.invitations.pending(uid)!!;assertEquals("assign-program-version",p.action);assertEquals(id,p.assignment?.templateId);assertEquals(other,p.assignment?.clientUid)
            val result=await<InvitationOutcome>{client.invitations.execute(uid,CommandResolution.resolveRequest(p),it)};assertNull(result.error)
            assertEquals(when(mode){"fenced"->"not-applied-fenced";"conflict","source"->"not-applied-conflict";else->"applied"},result.reply?.terminalOutcome?.outcome)
        }
        assertNull(client.invitations.pending(uid));val settled=client.invitations.lastSettled(uid)!!;assertTrue(settled.valid());assertNull(settled.command);val marker = assertNotNull(settled.marker)
        assertEquals(uid, marker.uid)
        assertEquals("program-assignment-create", marker.commandKind)
        assertEquals(when(mode){"fenced"->"not-applied-fenced";"conflict"->"not-applied-conflict";else->"applied"}, marker.outcome)
        println("M7P1B_MARKER ${marker.commandId} ${marker.requestHash}")
        val files=java.io.File(context.noBackupFilesDir,"tillfailure-recovery").listFiles()!!.filter{it.isFile}
        val archive=java.io.File(context.noBackupFilesDir,"evidence").apply{mkdirs()}
        files.forEach{file->val copy=java.io.File(archive,file.name);file.copyTo(copy,overwrite=false);assertTrue(file.readBytes().contentEquals(copy.readBytes()))}
        println("M7P1B_ANDROID_SETTLED_CHECKPOINT_VERIFIED")
        val rows=await<AssignmentReadResult>{repository.read(scope,AssignmentReadRequest("assignment-list",wid,other),it)};assertNull(rows.error);assertEquals(if(mode in setOf("fenced","conflict"))0 else 1,rows.headers.size)
        assertEquals(ProtectedRecoveryState.Clean,client.invitations.recoveryState(uid))
        main{root.identity.onEvent(IdentityEvent.SignOut)};until{root.identity.currentState().status==GateStatus.SignedOut}
        val next=root.identity.activeClient();main{root.clear()};assertNull(await<StableFirebaseFailure?>{next.signIn("$other@example.invalid",password,0,it)})
        main{root=NativeClientsModels(next)};until{root.identity.currentState().status==GateStatus.ClientHome}
        assertNull(next.invitations!!.pending(other));assertNull(next.invitations!!.lastSettled(other))
        val b=await<AssignmentReadResult>{next.assignments!!.read(ExerciseScope(other,wid,1),AssignmentReadRequest("client-assignment-list",wid,other),it)};assertNull(b.error)
        assertEquals(if(mode in setOf("fenced","conflict"))0 else 1,b.headers.size)
        if(b.headers.isNotEmpty()) {
            lateinit var model:AssignmentViewModel;main{model=AssignmentViewModel(next.assignments!!).also{it.attach(AssignmentScope(other,wid,other,"client",1))}};until{!model.state.value.busy}
            main{model.onEvent(AssignmentEvent.Open(b.headers.single().assignmentId))};until{!model.state.value.busy}
            assertTrue(model.state.value.content!=null && model.state.value.error==null);assertEquals(b.headers.single().snapshotHash,model.state.value.content?.let(AssignmentContract::copyHash));main{model.clear()}
        }
        val foreign=await<AssignmentReadResult>{next.assignments!!.read(ExerciseScope(other,wid,1),AssignmentReadRequest("assignment-list",wid,other),it)};assertNotNull(foreign.error)
        main{root.identity.onEvent(IdentityEvent.SignOut)};until{root.identity.currentState().status==GateStatus.SignedOut};main{root.clear()};println("M7P1B_ANDROID_PASS_${kind}_$mode")
    }
}
