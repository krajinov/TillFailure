package com.delminiusapps.tillfailure.firebase

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.identity.*
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.profile.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import kotlin.test.*

class AndroidCommandResolutionTest {
    private val host get()=InstrumentationRegistry.getInstrumentation()
    private fun main(block:()->Unit)=host.runOnMainSync(block)
    private fun until(check:()->Boolean) { val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);while(!check() && System.nanoTime()<end)Thread.sleep(50);assertTrue(check(),"Resolution native timeout") }
    private fun <T> await(start:((T)->Unit)->Unit):T {val latch=CountDownLatch(1);val result=AtomicReference<T>();start {result.set(it);latch.countDown()};assertTrue(latch.await(45,TimeUnit.SECONDS));return result.get()}
    @Test fun realCommandOutcomeAcrossProcessRestart() {
        val args=InstrumentationRegistry.getArguments();val phase=requireNotNull(args.getString("resolutionPhase"));val password=requireNotNull(args.getString("resolutionPassword"));val mode=phase.substringBefore('-')
        val prefix=args.getString("resolutionPrefix") ?: "m5r";require(Regex("^m5r[0-9]*$").matches(prefix))
        val base="${prefix}_android_$mode";val uid="${base}_client";val wid="${base}_workspace"
        val context=object:ContextWrapper(host.targetContext) {
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("${base}_$name",mode)
            override fun getNoBackupFilesDir()=java.io.File(super.getNoBackupFilesDir(),base).apply{mkdirs()}
        }
        val client=AndroidFirebaseSpikeClient(host.targetContext,FirebaseEmulatorConfiguration(host="10.0.2.2",authPort=29699,firestorePort=28680,functionsPort=25601),AccountCallbackFence(),productMemoryCache=true,overrideProductAppName=base,protectedContextForTest=context)
        if(!client.hasPinnedIdentity())assertNull(await<StableFirebaseFailure?> {client.signIn("${uid}@example.invalid",password,0,it)})
        lateinit var root:NativeProfileModels;main {root=NativeProfileModels(client)}
        until { root.identity.currentState().status in setOf(GateStatus.ClientHome,GateStatus.LockedRecovery,GateStatus.AccessLost,GateStatus.WorkspaceGate) }
        if(phase.endsWith("stage")) {
            val held=AtomicReference<()->Unit>()
            if(mode=="fenced")client.beforeInvitationTransportForTest={held.set(it)}
            else if(mode=="proof")client.beforeCommandRetirementForTest={println("M5R_ANDROID_PROOF_DURABLE");CountDownLatch(1).await(120,TimeUnit.SECONDS);error("Host must terminate")}
            else client.beforeInvitationCompletionForTest={held.set(it)}
            client.invitations.execute(uid,InvitationRequest("update-profile",workspaceId=wid,idempotencyKey=client.invitations.newCommandKey(),profileUpdate=ProfileUpdate("Resolved Android","bs-BA","Europe/Sarajevo",1))) {}
            if(mode!="proof") {
                until {held.get()!=null};assertNotNull(client.invitations.pending(uid))
                main {root.identity.onEvent(IdentityEvent.SignOut)};until {root.identity.currentState().status in setOf(GateStatus.LockedRecovery,GateStatus.DepartureBlocked)}
                assertEquals(StableFirebaseErrorCode.FAILED_PRECONDITION,await<StableFirebaseFailure?>{client.signIn("${base}_trainer@example.invalid",password,0,it)}?.code)
                println("M5R_ANDROID_ORIGINAL_HELD")
            }
            CountDownLatch(1).await(120,TimeUnit.SECONDS);error("Host must terminate stage")
        }
        if(mode=="proof") {assertNull(client.invitations.pending(uid));assertEquals("normal-v1",client.invitations.lastSettled(uid)?.source)}
        else {
            val original=client.invitations.pending(uid)!!;assertTrue(original.idempotencyKey.startsWith("or1-"));assertEquals(1,original.profileUpdate?.expectedRevision)
            main {root.identity.onEvent(IdentityEvent.SignOut)};until {root.identity.currentState().status in setOf(GateStatus.LockedRecovery,GateStatus.DepartureBlocked)}
            val result=await<InvitationOutcome> {client.invitations.execute(uid,CommandResolution.resolveRequest(original),it)}
            assertNull(result.error);assertEquals(if(mode=="applied")"applied" else "not-applied-fenced",result.reply?.terminalOutcome?.outcome)
            assertNull(client.invitations.pending(uid));assertEquals("resolution-v1",client.invitations.lastSettled(uid)?.source)
            assertFalse(client.invitations.execute(uid,CommandResolution.original(original)) {}.let {client.invitations.pending(uid)!=null})
        }
        main {root.clear();root=NativeProfileModels(client)};until {root.identity.currentState().status==GateStatus.AccessLost || root.identity.currentState().status==GateStatus.WorkspaceGate}
        assertNotEquals(GateStatus.ClientHome,root.identity.currentState().status)
        main {root.identity.onEvent(IdentityEvent.SignOut)};until {root.identity.currentState().status==GateStatus.SignedOut}
        val replacement=root.identity.activeClient();main {root.clear()}
        assertNull(await<StableFirebaseFailure?> {replacement.signIn("${base}_trainer@example.invalid",password,0,it)})
        main {root=NativeProfileModels(replacement)};until {root.identity.currentState().status==GateStatus.TrainerHome}
        assertNull(replacement.invitations!!.pending("${base}_trainer"));assertNull(replacement.invitations!!.lastSettled("${base}_trainer"))
        main {root.identity.onEvent(IdentityEvent.SignOut)};until {root.identity.currentState().status==GateStatus.SignedOut};main {root.clear()}
    }
}
