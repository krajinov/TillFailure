package com.delminiusapps.tillfailure.firebase

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.clients.*
import com.delminiusapps.tillfailure.identity.*
import com.delminiusapps.tillfailure.invitations.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import kotlin.test.*

/** Actual SDK/encrypted-store/process restart proof; not touch or OS observer evidence. */
class AndroidTrainerNotesTest {
    private val host get()=InstrumentationRegistry.getInstrumentation()
    private fun main(block:()->Unit)=host.runOnMainSync(block)
    private fun until(check:()->Boolean) {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);while(!check() && System.nanoTime()<end)Thread.sleep(50);assertTrue(check(),"Note native state timeout")}
    private fun <T> await(start:((T)->Unit)->Unit):T {val latch=CountDownLatch(1);val result=AtomicReference<T>();start {result.set(it);latch.countDown()};assertTrue(latch.await(45,TimeUnit.SECONDS));return result.get()}
    @Test fun notesAcrossProcessRestart() {
        val args=InstrumentationRegistry.getArguments();val phase=requireNotNull(args.getString("pr5Phase"));val password=requireNotNull(args.getString("pr5Password"));val prefix=requireNotNull(args.getString("pr5Prefix"))
        require(Regex("^m5p5r[0-9]{6,20}$").matches(prefix));val mode=phase.substringBefore('-');val base="${prefix}_android_$mode";val uid="${base}_trainer";val target="${base}_client";val wid="${base}_workspace"
        val context=object:ContextWrapper(host.targetContext) {
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("${base}_$name",mode)
            override fun getNoBackupFilesDir()=java.io.File(super.getNoBackupFilesDir(),base).apply{mkdirs()}
        }
        val client=AndroidFirebaseSpikeClient(host.targetContext,FirebaseEmulatorConfiguration(host="10.0.2.2",authPort=30699,firestorePort=30680,functionsPort=30601),AccountCallbackFence(),productMemoryCache=true,overrideProductAppName=base,protectedContextForTest=context)
        if(!client.hasPinnedIdentity())assertNull(await<StableFirebaseFailure?>{client.signIn("$uid@example.invalid",password,0,it)})
        lateinit var root:NativeClientsModels;main {root=NativeClientsModels(client)}
        until {root.identity.currentState().status in setOf(GateStatus.TrainerHome,GateStatus.LockedRecovery,GateStatus.AccessLost,GateStatus.WorkspaceGate)}
        if(phase.endsWith("stage")) {
            val read=await<InvitationOutcome>{client.invitations.execute(uid,TrainerNoteProtocol.read(wid,target),it)};assertNull(read.error)
            val original=TrainerNoteProtocol.saveFromRead(read.reply!!.noteRead!!,"Review demonstration next meeting.",client.invitations.newNoteBindingNonce(),client.invitations.newCommandKey())
            if(mode=="conflict")assertNull(await<InvitationOutcome>{client.invitations.execute(uid,TrainerNoteProtocol.saveFromRead(read.reply!!.noteRead!!,"Review session plan.",client.invitations.newNoteBindingNonce(),client.invitations.newCommandKey()),it)}.error)
            val held=AtomicReference<()->Unit>()
            when(mode) {
                "fenced" -> client.beforeInvitationTransportForTest={held.set(it)}
                "proof" -> client.beforeCommandRetirementForTest={println("M5P5_ANDROID_PROOF_DURABLE");CountDownLatch(1).await(180,TimeUnit.SECONDS);error("Host must terminate")}
                "settled" -> Unit
                else -> client.beforeInvitationCompletionForTest={held.set(it)}
            }
            if(mode=="settled") {assertNull(await<InvitationOutcome>{client.invitations.execute(uid,original,it)}.error);assertNull(client.invitations.lastSettled(uid)!!.command);println("M5P5_ANDROID_SETTLED")}
            else {
                main {client.invitations.execute(uid,original){}}
                if(mode!="proof") {
                    until {held.get()!=null};assertEquals(original.pending(uid),client.invitations.pending(uid))
                    main {root.identity.onEvent(IdentityEvent.SignOut)};until {root.identity.currentState().status in setOf(GateStatus.LockedRecovery,GateStatus.DepartureBlocked)}
                    assertEquals(StableFirebaseErrorCode.FAILED_PRECONDITION,await<StableFirebaseFailure?>{client.signIn("$target@example.invalid",password,0,it)}?.code)
                    println("M5P5_ANDROID_ORIGINAL_HELD")
                }
            }
            CountDownLatch(1).await(180,TimeUnit.SECONDS);error("Host must terminate stage")
        }
        if(mode !in setOf("proof","settled")) {
            val p=client.invitations.pending(uid)!!;assertEquals("save-trainer-note",p.action);assertEquals(target,p.noteSave?.clientUid);assertEquals("Review demonstration next meeting.",p.noteSave?.body)
            val outcome=await<InvitationOutcome>{client.invitations.execute(uid,CommandResolution.resolveRequest(p),it)};assertNull(outcome.error)
            assertEquals(when(mode){"fenced"->"not-applied-fenced";"conflict"->"not-applied-conflict";else->"applied"},outcome.reply?.terminalOutcome?.outcome)
            client.invitations.execute(uid,CommandResolution.original(p)){};assertNull(client.invitations.pending(uid))
        }
        assertNull(client.invitations.pending(uid));val proof=client.invitations.lastSettled(uid)!!;assertTrue(proof.valid());assertNull(proof.command);assertNotNull(proof.marker)
        main {root.clear();root=NativeClientsModels(client)};until {root.identity.currentState().status in setOf(GateStatus.AccessLost,GateStatus.WorkspaceGate)}
        assertNotEquals(GateStatus.TrainerHome,root.identity.currentState().status)
        main {root.identity.onEvent(IdentityEvent.SignOut)};until {root.identity.currentState().status==GateStatus.SignedOut}
        val replacement=root.identity.activeClient();main {root.clear()};assertNull(await<StableFirebaseFailure?>{replacement.signIn("$target@example.invalid",password,0,it)})
        main {root=NativeClientsModels(replacement)};until {root.identity.currentState().status in setOf(GateStatus.ClientHome,GateStatus.Onboarding)}
        assertNull(replacement.invitations!!.pending(target));assertNull(replacement.invitations!!.lastSettled(target))
        main {root.identity.onEvent(IdentityEvent.SignOut)};until {root.identity.currentState().status==GateStatus.SignedOut};main {root.clear()}
        println("M5P5_ANDROID_PASS_$mode")
    }
}
