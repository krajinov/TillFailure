package com.delminiusapps.tillfailure.firebase

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.exercises.*
import com.delminiusapps.tillfailure.programs.*
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
class AndroidProgramDraftsTest {
    private val host get()=InstrumentationRegistry.getInstrumentation()
    private fun main(block:()->Unit)=host.runOnMainSync(block)
    private fun until(check:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);while(!check() && System.nanoTime()<end)Thread.sleep(50);assertTrue(check(),"Draft native state timeout")}
    private fun <T> await(start:((T)->Unit)->Unit):T {val latch=CountDownLatch(1);val result=AtomicReference<T>();start{result.set(it);latch.countDown()};assertTrue(latch.await(45,TimeUnit.SECONDS));return result.get()}
    @Test fun commandsAcrossProcessRestart() {
        val args=InstrumentationRegistry.getArguments();val phase=requireNotNull(args.getString("m6p3Phase"));val prefix=requireNotNull(args.getString("m6p3Prefix"));val password=requireNotNull(args.getString("m6p3Password"))
        require(Regex("m6p3r[0-9a-z]{6,24}").matches(prefix));val parts=phase.split('-');val kind=parts[0];val mode=parts[1];val base="${prefix}_android_${kind}_$mode";val uid=base;val wid="${uid}_ws";val other="${prefix}_android_other"
        val context=object:ContextWrapper(host.targetContext){override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("${base}_$name",mode);override fun getNoBackupFilesDir()=java.io.File(super.getNoBackupFilesDir(),base).apply{mkdirs()}}
        val client=AndroidFirebaseSpikeClient(host.targetContext,FirebaseEmulatorConfiguration(host="10.0.2.2",authPort=42699,firestorePort=42680,functionsPort=42601),AccountCallbackFence(),productMemoryCache=true,overrideProductAppName=base,protectedContextForTest=context)
        if(!client.hasPinnedIdentity())assertNull(await<StableFirebaseFailure?>{client.signIn("$uid@example.invalid",password,0,it)});println("M6P3_ANDROID_AUTH_READY")
        val fresh=await<FirebaseSessionRefresh>{client.refreshSession(0,it)};assertNull(fresh.failure);println("M6P3_ANDROID_REFRESH_READY")
        var account=await<FirebaseDocumentResult>{client.getDocument("users/$uid",0,it)}
        // A cold AVD can spend the SDK's first ten-second deadline verifying gRPC classes.
        // Retry fresh SERVER reads at most twice; never accept cache or change identity/history.
        repeat(2){if(account.failure!=null){Thread.sleep(500);account=await<FirebaseDocumentResult>{client.getDocument("users/$uid",0,it)}}}
        assertNull(account.failure);println("M6P3_ANDROID_ACCOUNT_READY")
        lateinit var root:NativeClientsModels;main{root=NativeClientsModels(client)};until{root.identity.currentState().status in setOf(GateStatus.TrainerHome,GateStatus.LockedRecovery)}
        val repository=client.drafts;val scope=ExerciseScope(uid,wid,1);val id="00000000-0000-4000-8000-000000000001"
        fun command(revision:Int, source:Boolean=false):InvitationRequest {
            val prep=await<DraftReadResult>{repository.read(scope,DraftReadRequest(wid,"draft-prepare"),it)};assertNull(prep.error)
            return DraftProtocol.write(wid,DraftSave(id,revision,prep.authority!!,"Synthetic native","Original restart demonstration.",if(source)listOf(DraftWorkout("00000000-0000-4000-8000-000000000002","",listOf(DraftItem("00000000-0000-4000-8000-000000000003","system","missing-native-source",null,"a".repeat(64),"Synthetic",DraftPrescription(null,null,null,null))))) else emptyList()),client.invitations.newCommandKey())
        }
        if(phase.endsWith("stage")) {
            assertTrue(client.freeze(uid));assertNotNull(await<DraftReadResult>{repository.read(scope,DraftReadRequest(wid,"draft-prepare"),it)}.error);client.unfreeze(uid)
            if(kind=="correct")assertNull(await<InvitationOutcome>{client.invitations.execute(uid,command(0),it)}.error)
            val original=command(if(kind=="correct")1 else 0,mode=="source")
            if(mode=="conflict")assertNull(await<InvitationOutcome>{client.invitations.execute(uid,command(if(kind=="correct")1 else 0),it)}.error)
            val held=AtomicReference<()->Unit>()
            when(mode) {
                "fenced"->client.beforeInvitationTransportForTest={held.set(it)}
                "proof"->client.beforeCommandRetirementForTest={println("M6P3_ANDROID_PROOF_DURABLE");CountDownLatch(1).await(180,TimeUnit.SECONDS);error("Host must terminate")}
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
                assertEquals(0,callbacks.get());client.beforeInvitationTransportForTest=null;client.unfreeze(uid);println("M6P3_ANDROID_READY_admission")
            } else if(mode in setOf("settled","failure")) {
                val result=await<InvitationOutcome>{client.invitations.execute(uid,original,it)}
                if(mode=="failure"){assertEquals("storage-unavailable",result.error);assertEquals(original.pending(uid),client.invitations.pending(uid))}
                else {assertNull(result.error);assertNull(client.invitations.lastSettled(uid)!!.command)}
                println("M6P3_ANDROID_READY_$mode")
            } else {
                main{client.invitations.execute(uid,original){}}
                if(mode!="proof") {until{held.get()!=null};assertEquals(original.pending(uid),client.invitations.pending(uid));main{root.identity.onEvent(IdentityEvent.SignOut)};until{root.identity.currentState().status in setOf(GateStatus.LockedRecovery,GateStatus.DepartureBlocked)}
                    assertNotNull(await<StableFirebaseFailure?>{client.signIn("$other@example.invalid",password,0,it)});println("M6P3_ANDROID_ORIGINAL_HELD")}
            }
            CountDownLatch(1).await(180,TimeUnit.SECONDS);error("Host must terminate stage")
        }
        if(mode !in setOf("proof","settled","admission")) {
            val p=client.invitations.pending(uid)!!;assertEquals("save-program-draft",p.action);assertEquals("Original restart demonstration.",p.draftSave?.description)
            val result=await<InvitationOutcome>{client.invitations.execute(uid,CommandResolution.resolveRequest(p),it)};assertNull(result.error)
            assertEquals(when(mode){"fenced"->"not-applied-fenced";"conflict","source"->"not-applied-conflict";else->"applied"},result.reply?.terminalOutcome?.outcome)
        }
        assertNull(client.invitations.pending(uid));val settled=client.invitations.lastSettled(uid)!!;assertTrue(settled.valid());assertNull(settled.command);assertNotNull(settled.marker)
        val detail=await<DraftReadResult>{repository.read(scope,DraftReadRequest(wid,"draft-get",templateId=id),it)}
        if(kind=="create" && mode in setOf("fenced","source"))assertNotNull(detail.error) else assertNotNull(detail.draft)
        assertEquals(ProtectedRecoveryState.Clean,client.invitations.recoveryState(uid))
        main{root.identity.onEvent(IdentityEvent.SignOut)};until{root.identity.currentState().status==GateStatus.SignedOut}
        val next=root.identity.activeClient();main{root.clear()};assertNull(await<StableFirebaseFailure?>{next.signIn("$other@example.invalid",password,0,it)})
        main{root=NativeClientsModels(next)};until{root.identity.currentState().status==GateStatus.TrainerHome}
        assertNull(next.invitations!!.pending(other));assertNull(next.invitations!!.lastSettled(other))
        val b=await<DraftReadResult>{next.drafts!!.read(ExerciseScope(other,"${other}_ws",1),DraftReadRequest("${other}_ws","draft-list"),it)};assertTrue(b.items.isEmpty())
        main{root.identity.onEvent(IdentityEvent.SignOut)};until{root.identity.currentState().status==GateStatus.SignedOut};main{root.clear()};println("M6P3_ANDROID_PASS_${kind}_$mode")
    }
}
