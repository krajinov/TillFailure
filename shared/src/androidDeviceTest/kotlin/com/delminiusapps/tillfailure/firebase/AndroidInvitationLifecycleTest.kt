package com.delminiusapps.tillfailure.firebase

import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.content.ContextWrapper
import com.delminiusapps.tillfailure.designcatalog.NativeInvitationModels
import com.delminiusapps.tillfailure.identity.*
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.AndroidAtomicFilePersistence
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Stage is force-stopped by the host while a real committed response is held in memory. */
class AndroidInvitationLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    // A dedicated real app-private namespace preserves older PR 1/2 test partitions and pins.
    // It survives process death and still uses the actual SDK and Keystore-backed file store.
    private val context by lazy {
        object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("pr3_finalize_$name", mode)
            override fun getNoBackupFilesDir() = java.io.File(super.getNoBackupFilesDir(), "pr3-finalize").apply { mkdirs() }
            override fun getFilesDir() = java.io.File(super.getFilesDir(), "pr3-finalize").apply { mkdirs() }
        }
    }
    private val password get() = InstrumentationRegistry.getArguments().getString("pr3Password")!!
    private val marker get() = java.io.File(context.filesDir, "pr3-invitation-lifecycle-status.txt")
    private fun fresh() = AndroidFirebaseSpikeClient(instrumentation.targetContext, FirebaseEmulatorConfiguration(host = "10.0.2.2"),
        AccountCallbackFence(), productMemoryCache = true, protectedContextForTest = context)
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun ready(value: String) { marker.writeText(value); println("PR3_ANDROID $value") }
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
        while (!condition() && System.nanoTime() < end) Thread.sleep(30)
        assertTrue(condition(), "Native lifecycle condition timed out")
    }
    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1); val result = AtomicReference<T>()
        main { start { result.set(it); latch.countDown() } }
        assertTrue(latch.await(35, TimeUnit.SECONDS), "Native callback timed out")
        return result.get()
    }
    private fun entry(client: AndroidFirebaseSpikeClient, kind: String): String {
        val token = com.google.android.gms.tasks.Tasks.await(FirebaseAuth.getInstance(client.firebaseApp).currentUser!!.getIdToken(true), 15, TimeUnit.SECONDS).token!!
        val connection = URL("http://10.0.2.2:9693/fixture/android/$kind").openConnection() as HttpURLConnection
        return try {
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            assertEquals(200, connection.responseCode)
            org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("entry")
        } finally { connection.disconnect() }
    }
    private fun begin(client: AndroidFirebaseSpikeClient, address: String): NativeInvitationModels {
        assertNull(await<StableFirebaseFailure?> { client.signIn(address, password, 0, it) })
        lateinit var models: NativeInvitationModels
        main { models = NativeInvitationModels(client) }
        waitUntil { models.identity.currentState().status == GateStatus.NoAccount }
        main { models.synchronize() }
        return models
    }
    private fun preview(models: NativeInvitationModels, value: String) {
        main { models.invitation.onEvent(InvitationEvent.Entry(value)); models.invitation.onEvent(InvitationEvent.Open) }
        waitUntil { models.invitation.currentState().workspaceName != null && !models.invitation.currentState().busy }
    }
    private fun proveBlocked(models: NativeInvitationModels, client: AndroidFirebaseSpikeClient) {
        main { models.identity.onEvent(IdentityEvent.SignOut) }
        waitUntil { models.identity.currentState().status == GateStatus.DepartureBlocked }
        assertEquals(StableFirebaseErrorCode.FAILED_PRECONDITION,
            await<StableFirebaseFailure?> { client.signIn("pr3-android-trainer@example.invalid", password, 0, it) }?.code)
        assertEquals(models.identity.currentState().accountUid, FirebaseAuth.getInstance(client.firebaseApp).currentUser?.uid)
    }
    @Test fun stageCommittedResponseBeforeActualProcessKill() {
        val client = fresh(); val models = begin(client, "pr3-android-client@example.invalid")
        val value = entry(client, "restart"); preview(models, value)
        val held = AtomicReference<() -> Unit>()
        client.beforeInvitationCompletionForTest = { held.set(it) }
        main { models.invitation.onEvent(InvitationEvent.Accept); models.invitation.onEvent(InvitationEvent.Confirm) }
        waitUntil { held.get() != null }
        assertEquals("accept", client.invitations.pending("pr3_android_client")?.action)
        val record = AndroidAtomicFilePersistence(context).read("pr3_android_client")!!
        assertFalse(kotlinx.serialization.json.Json.encodeToString(com.delminiusapps.tillfailure.persistence.AccountPersistenceEnvelope.serializer(), record)
            .contains(InvitationLink.parse(value)!!.token), "Protected metadata must not persist a token")
        assertNull(models.identity.currentState().verifiedRole)
        proveBlocked(models, client)
        ready("RESTART_RESPONSE_HELD")
        // Host terminates this process at the marker, rather than allowing a normal settlement.
        CountDownLatch(1).await(120, TimeUnit.SECONDS)
        error("Host did not interrupt the unresolved process")
    }
    @Test fun resumeOriginalCommandAfterActualProcessRestart() {
        val client = fresh()
        assertEquals("pr3_android_client", FirebaseAuth.getInstance(client.firebaseApp).currentUser?.uid)
        assertNull(client.invitations.memory.link)
        val original = client.invitations.pending("pr3_android_client")!!
        com.google.android.gms.tasks.Tasks.await(FirebaseFirestore.getInstance(client.firebaseApp).disableNetwork(), 10, TimeUnit.SECONDS)
        lateinit var models: NativeInvitationModels
        main { models = NativeInvitationModels(client) }
        waitUntil { models.identity.currentState().status == GateStatus.ConnectToVerify }
        assertNull(models.identity.currentState().verifiedRole)
        main { models.synchronize() }
        proveBlocked(models, client)
        val value = entry(client, "restart")
        main { models.invitation.onEvent(InvitationEvent.Entry(value)); models.invitation.onEvent(InvitationEvent.Open) }
        assertEquals(original, models.invitation.currentState().pending)
        com.google.android.gms.tasks.Tasks.await(FirebaseFirestore.getInstance(client.firebaseApp).enableNetwork(), 10, TimeUnit.SECONDS)
        main { models.identity.onEvent(IdentityEvent.Retry) }
        waitUntil { models.identity.currentState().status == GateStatus.MembershipVerifiedFeaturePending }
        main { models.synchronize() }
        client.beforeInvitationTransportForTest = { issue ->
            assertEquals(original, client.invitations.pending("pr3_android_client")); issue()
        }
        main { models.invitation.onEvent(InvitationEvent.RetryCommand) }
        waitUntil { !models.invitation.currentState().busy && client.invitations.pending("pr3_android_client") == null }
        assertTrue(models.invitation.currentState().message.startsWith("Invitation accepted"))
        assertNull(client.invitations.memory.link)
        main { models.identity.onEvent(IdentityEvent.Retry) }
        waitUntil { models.identity.currentState().verifiedRole == "client" }
        main { models.identity.onEvent(IdentityEvent.SignOut) }
        waitUntil { models.identity.currentState().status == GateStatus.SignedOut }
        ready("RESTART_REPLAY_AND_FRESH_GATE_PASS")
        main { models.clear() }
    }
    @Test fun lateCompletionCannotReachClearedRootOrAccountB() {
        val client = fresh(); val old = begin(client, "pr3-android-late@example.invalid")
        preview(old, entry(client, "late"))
        val transport = AtomicReference<() -> Unit>(); val completion = AtomicReference<() -> Unit>()
        client.beforeInvitationTransportForTest = { transport.set(it) }
        client.beforeInvitationCompletionForTest = { completion.set(it) }
        main { old.invitation.onEvent(InvitationEvent.Accept); old.invitation.onEvent(InvitationEvent.Confirm) }
        waitUntil { transport.get() != null }
        val frozen = old.invitation.currentState()
        val pending = client.invitations.pending("pr3_android_late")!!
        main { old.clear() }
        lateinit var recreated: NativeInvitationModels
        main { recreated = NativeInvitationModels(client) }
        waitUntil { recreated.identity.currentState().status == GateStatus.NoAccount }
        main { recreated.synchronize(); recreated.invitation.onEvent(InvitationEvent.RetryCommand) }
        assertEquals(pending, client.invitations.pending("pr3_android_late"))
        proveBlocked(recreated, client)
        main { transport.get().invoke() }
        waitUntil { completion.get() != null }
        assertEquals(frozen, old.invitation.currentState())
        main { completion.get().invoke() }
        waitUntil { client.invitations.pending("pr3_android_late") == null }
        assertEquals(frozen, old.invitation.currentState())
        assertNull(recreated.invitation.currentState().pending)
        assertNull(recreated.invitation.currentState().workspaceName)
        assertNull(recreated.invitation.currentState().deliveryLink)
        main { recreated.identity.onEvent(IdentityEvent.Retry) }
        waitUntil { recreated.identity.currentState().verifiedRole == "client" }
        main { recreated.identity.onEvent(IdentityEvent.SignOut) }
        waitUntil { recreated.identity.currentState().status == GateStatus.SignedOut }
        val b = recreated.identity.activeClient()
        assertNull(await<StableFirebaseFailure?> { b.signIn("pr3-android-trainer@example.invalid", password, 0, it) })
        waitUntil { recreated.identity.currentState().status == GateStatus.TrainerHome }
        main { recreated.synchronize() }
        val stateB = recreated.invitation.currentState()
        main { completion.get().invoke() }
        assertEquals(stateB, recreated.invitation.currentState())
        assertEquals(frozen, old.invitation.currentState())
        assertNull(stateB.workspaceName); assertNull(stateB.deliveryLink); assertTrue(stateB.summaries.isEmpty())
        main { recreated.identity.onEvent(IdentityEvent.SignOut) }
        waitUntil { recreated.identity.currentState().status == GateStatus.SignedOut }
        ready("CLEARED_ROOT_PENDING_DEPARTURE_AND_A_B_FENCE_PASS")
        main { recreated.clear() }
    }
}
