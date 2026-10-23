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

/** SDK/model integration with host process termination, separate from touch evidence. */
class AndroidClientRevocationTest {
    private val host get()=InstrumentationRegistry.getInstrumentation()
    private fun main(block:()->Unit)=host.runOnMainSync(block)
    private fun until(check:()->Boolean) {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);while(!check() && System.nanoTime()<end)Thread.sleep(50);assertTrue(check(),"PR 4 native state timeout")}
    private fun <T> await(start:((T)->Unit)->Unit):T {val latch=CountDownLatch(1);val result=AtomicReference<T>();start {result.set(it);latch.countDown()};assertTrue(latch.await(45,TimeUnit.SECONDS));return result.get()}
    private fun client(base:String):AndroidFirebaseSpikeClient {
        val context=object:ContextWrapper(host.targetContext) {
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("${base}_$name",mode)
            override fun getNoBackupFilesDir()=java.io.File(super.getNoBackupFilesDir(),base).apply{mkdirs()}
        }
        return AndroidFirebaseSpikeClient(host.targetContext,FirebaseEmulatorConfiguration(host="10.0.2.2",authPort=30699,firestorePort=30680,functionsPort=30601),AccountCallbackFence(),productMemoryCache=true,overrideProductAppName=base,protectedContextForTest=context)
    }
    @Test fun revocationAcrossProcessRestartAndLiveAuthorityLoss() {
        val args=InstrumentationRegistry.getArguments();val phase=requireNotNull(args.getString("pr4Phase"));val password=requireNotNull(args.getString("pr4Password"))
        val prefix=requireNotNull(args.getString("pr4Prefix"));require(Regex("^m5p4r[0-9]{6,20}$").matches(prefix))
        val mode=phase.substringBefore('-');val base="${prefix}_android_$mode";val uid="${base}_trainer";val target="${base}_client";val wid="${base}_workspace"
        val client=client(base)
        if(!client.hasPinnedIdentity())assertNull(await<StableFirebaseFailure?>{client.signIn("$uid@example.invalid",password,0,it)})
        lateinit var root:NativeClientsModels;main {root=NativeClientsModels(client)}
        until {root.identity.currentState().status in setOf(GateStatus.TrainerHome,GateStatus.LockedRecovery,GateStatus.AccessLost,GateStatus.WorkspaceGate)}
        if(mode=="live") {
            // A separate test installation/SDK partition observes the affected client's live gate.
            val observer=client(base+"_observer");assertNull(await<StableFirebaseFailure?>{observer.signIn("$target@example.invalid",password,0,it)})
            lateinit var observed:NativeClientsModels;main {observed=NativeClientsModels(observer)};until {observed.identity.currentState().status==GateStatus.MultipleMemberships};main {observed.identity.onEvent(IdentityEvent.ChooseWorkspace(wid))};until {observed.identity.currentState().status==GateStatus.ClientHome}
            val prepared=await<InvitationOutcome>{client.invitations.execute(uid,ClientRevocationProtocol.prepare(wid,target),it)}
            assertNull(prepared.error)
            val result=await<InvitationOutcome>{client.invitations.execute(uid,ClientRevocationProtocol.command(wid,prepared.reply!!.revocationPreparation!!,client.invitations.newCommandKey()),it)}
            assertEquals("client-revoked",result.reply?.revocationDecision?.status)
            until {observed.identity.currentState().status in setOf(GateStatus.AccessLost,GateStatus.WorkspaceGate)}
            assertTrue(observer.hasPinnedIdentity());assertNotEquals(GateStatus.ClientHome,observed.identity.currentState().status)
            main {observed.identity.onEvent(IdentityEvent.ChangeWorkspace)};until {observed.identity.currentState().status==GateStatus.ClientHome}
            assertEquals(base+"_other_workspace",observed.identity.currentState().verifiedWorkspaceId)
            main {observed.identity.onEvent(IdentityEvent.SignOut)};until {observed.identity.currentState().status==GateStatus.SignedOut};main {observed.clear()}
        } else if(phase.endsWith("stage")) {
            main {root.details.bind(ClientScope(uid,wid,uid,root.identity.currentState().authorityEpoch),target)}
            until {root.details.currentState().revocationPreparation != null}
            val held=AtomicReference<()->Unit>()
            if(mode=="fenced")client.beforeInvitationTransportForTest={held.set(it)}
            else if(mode=="proof")client.beforeCommandRetirementForTest={println("M5P4_ANDROID_PROOF_DURABLE");CountDownLatch(1).await(120,TimeUnit.SECONDS);error("Host must terminate")}
            else client.beforeInvitationCompletionForTest={held.set(it)}
            main {root.details.onEvent(ClientsEvent.Revoke);val p=root.details.currentState().revocationPrompt!!;root.details.onEvent(ClientsEvent.CancelRevocation(p));root.details.onEvent(ClientsEvent.Revoke);val confirmed=root.details.currentState().revocationPrompt!!;root.details.onEvent(ClientsEvent.ConfirmRevocation(confirmed));root.details.onEvent(ClientsEvent.ConfirmRevocation(confirmed))}
            if(mode!="proof") {
                until {held.get()!=null};assertEquals("revoke-client",client.invitations.pending(uid)?.action)
                main {root.identity.onEvent(IdentityEvent.SignOut)};until {root.identity.currentState().status in setOf(GateStatus.LockedRecovery,GateStatus.DepartureBlocked)}
                assertEquals(StableFirebaseErrorCode.FAILED_PRECONDITION,await<StableFirebaseFailure?>{client.signIn("$target@example.invalid",password,0,it)}?.code)
                println("M5P4_ANDROID_ORIGINAL_HELD")
            }
            CountDownLatch(1).await(120,TimeUnit.SECONDS);error("Host must terminate stage")
        } else if(mode=="proof") {assertNull(client.invitations.pending(uid));assertTrue(client.invitations.lastSettled(uid)!!.valid())}
        else {
            val original=client.invitations.pending(uid)!!;assertEquals("revoke-client",original.action);assertEquals(target,original.revocation?.clientUid)
            val result=await<InvitationOutcome>{client.invitations.execute(uid,CommandResolution.resolveRequest(original),it)}
            assertNull(result.error);assertEquals(if(mode=="applied")"applied" else "not-applied-fenced",result.reply?.terminalOutcome?.outcome)
            assertNull(client.invitations.pending(uid));assertTrue(client.invitations.lastSettled(uid)!!.valid())
            client.invitations.execute(uid,CommandResolution.original(original)){};assertNull(client.invitations.pending(uid))
        }
        main {root.clear();root=NativeClientsModels(client)}
        until {root.identity.currentState().status in setOf(GateStatus.TrainerHome,GateStatus.AccessLost,GateStatus.WorkspaceGate)}
        if(mode!="live")assertNotEquals(GateStatus.TrainerHome,root.identity.currentState().status)
        main {root.identity.onEvent(IdentityEvent.SignOut)};until {root.identity.currentState().status==GateStatus.SignedOut}
        val replacement=root.identity.activeClient();main {root.clear()}
        assertNull(await<StableFirebaseFailure?>{replacement.signIn("$target@example.invalid",password,0,it)})
        main {root=NativeClientsModels(replacement)};until {root.identity.currentState().status in setOf(GateStatus.ClientHome,GateStatus.Onboarding,GateStatus.AccessLost,GateStatus.WorkspaceGate)}
        assertNull(replacement.invitations!!.pending(target));assertNull(replacement.invitations!!.lastSettled(target))
        main {root.identity.onEvent(IdentityEvent.SignOut)};until {root.identity.currentState().status==GateStatus.SignedOut};main {root.clear()}
    }
}
