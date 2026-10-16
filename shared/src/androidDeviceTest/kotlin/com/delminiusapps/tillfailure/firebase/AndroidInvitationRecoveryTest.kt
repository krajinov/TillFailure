package com.delminiusapps.tillfailure.firebase

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.designcatalog.NativeInvitationModels
import com.delminiusapps.tillfailure.identity.*
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.persistence.AndroidAtomicFilePersistence
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Real native SDK/gate/protected storage; host-committed lost replies and explicit wire faults. */
class AndroidInvitationRecoveryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context by lazy {
        object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("p1_review_$name", mode)
            override fun getNoBackupFilesDir() = java.io.File(super.getNoBackupFilesDir(), "p1-review").apply { mkdirs() }
            override fun getFilesDir() = java.io.File(super.getFilesDir(), "p1-review").apply { mkdirs() }
        }
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1); val result = AtomicReference<T>()
        main { start { result.set(it); latch.countDown() } }
        assertTrue(latch.await(35, TimeUnit.SECONDS), "Native recovery callback timed out")
        return result.get()
    }
    private fun gate(model: IdentityViewModel, expected: GateStatus) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
        while (model.currentState().status != expected && System.nanoTime() < end) Thread.sleep(30)
        assertEquals(expected, model.currentState().status)
    }
    private fun fixture(client: AndroidFirebaseSpikeClient, action: String, operation: String, mode: String? = null): String {
        val token = Tasks.await(FirebaseAuth.getInstance(client.firebaseApp).currentUser!!.getIdToken(true), 15, TimeUnit.SECONDS).token!!
        val connection = URL("http://10.0.2.2:9695/review/android/$action/$operation").openConnection() as HttpURLConnection
        return try {
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = 5000; connection.readTimeout = 15000
            if (operation != "evidence") {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write((mode?.let { "{\"mode\":\"$it\"}" } ?: "{}").toByteArray()) }
            }
            assertEquals(200, connection.responseCode)
            connection.inputStream.bufferedReader().use { it.readText().also { value -> check(value.length <= 8192) } }
        } finally { connection.disconnect() }
    }
    private fun guardedRoot(client: AndroidFirebaseSpikeClient, actor: IdentityViewModel, uid: String,
                            request: InvitationRequest): NativeInvitationModels {
        val retained = client.invitations.memory.linkFor(uid)!!
        val original = client.invitations.pending(uid)!!
        lateinit var old: NativeInvitationModels
        lateinit var root: NativeInvitationModels
        main {
            old = NativeInvitationModels(client); old.invitation.updateIdentity(actor.currentState()); old.clear()
            root = NativeInvitationModels(client); root.invitation.updateIdentity(actor.currentState())
            val otherId = retained.invitationId.replaceRange(0, 1, if (retained.invitationId[0] == 'f') "e" else "f")
            val candidates = listOf(InvitationLink(otherId, retained.version, "z".repeat(43)).entry(),
                InvitationLink(retained.invitationId, retained.version + 1, retained.token).entry(), "invalid-entry")
            for (candidate in candidates) {
                old.invitation.onEvent(InvitationEvent.Entry(candidate)); old.invitation.onEvent(InvitationEvent.Open)
                root.invitation.onEvent(InvitationEvent.Entry(candidate)); root.invitation.onEvent(InvitationEvent.Open)
                assertTrue(client.invitations.memory.link === retained, "Rejected entry must preserve the original bearer")
                assertEquals(original, client.invitations.pending(uid))
                assertEquals(original, root.invitation.currentState().pending)
            }
            assertNull(client.invitations.memory.linkFor("another-account"))
            assertFalse(client.invitations.memory.clear("another-account"))
            root.invitation.onEvent(InvitationEvent.PopulateEntry); root.invitation.onEvent(InvitationEvent.Open)
            assertTrue(client.invitations.memory.linkFor(uid)?.token == request.token)
            assertFalse(root.invitation.currentState().toString().contains(retained.token))
        }
        assertFalse(client.hasProvenEmptyCriticalWork(uid))
        val encoded = InvitationJson.format.encodeToString(com.delminiusapps.tillfailure.persistence.AccountPersistenceEnvelope.serializer(),
            AndroidAtomicFilePersistence(context).read(uid)!!)
        assertFalse(encoded.contains(retained.token))
        return root
    }
    private fun restoredEntry(client: AndroidFirebaseSpikeClient, actor: IdentityViewModel, uid: String,
                              request: InvitationRequest) {
        main {
            val root = NativeInvitationModels(client)
            // Entry presentation starts before the authenticated gate supplies the restored UID.
            val forged = InvitationLink(request.invitationId!!, request.expectedVersion!!, "z".repeat(43))
            root.invitation.onEvent(InvitationEvent.Entry(forged.entry())); root.invitation.onEvent(InvitationEvent.Open)
            assertTrue(client.invitations.memory.link?.token == forged.token)
            val original = client.invitations.pending(uid)
            root.invitation.updateIdentity(actor.currentState())
            assertNull(client.invitations.memory.link, "Safe metadata cannot verify a pre-auth bearer")
            assertFalse(root.invitation.currentState().hasLink)
            assertEquals(original, root.invitation.currentState().pending)
            root.invitation.onEvent(InvitationEvent.RetryCommand)
            assertFalse(root.invitation.currentState().busy)
            assertEquals(original, client.invitations.pending(uid))
            assertFalse(client.hasProvenEmptyCriticalWork(uid))
            val entry = InvitationLink(request.invitationId, request.expectedVersion, request.token!!)
            root.invitation.onEvent(InvitationEvent.Entry(entry.entry())); root.invitation.onEvent(InvitationEvent.Open)
            assertTrue(client.invitations.memory.linkFor(uid)?.token == request.token)
            assertEquals(original, client.invitations.pending(uid))
            root.clear()
        }
    }
    @Test fun allMutationErrorsPreserveProtectedWorkUntilAuthorizedReceiptRecovery() {
        val password = InstrumentationRegistry.getArguments().getString("pr3Password")!!
        var client = AndroidFirebaseSpikeClient(instrumentation.targetContext,
            FirebaseEmulatorConfiguration(host = "10.0.2.2", functionsPort = 9695), AccountCallbackFence(),
            productMemoryCache = true, protectedContextForTest = context)
        assertNull(await<StableFirebaseFailure?> { client.signIn("p1-android-trainer@example.invalid", password, 0, it) })
        lateinit var model: IdentityViewModel
        main { model = IdentityViewModel(client) }; gate(model, GateStatus.TrainerHome)
        for (action in listOf("create", "resend", "revoke", "accept")) {
            if (action == "accept") {
                main { model.onEvent(IdentityEvent.SignOut) }; gate(model, GateStatus.SignedOut)
                client = model.activeClient() as AndroidFirebaseSpikeClient
                assertNull(await<StableFirebaseFailure?> { client.signIn("p1-android-client@example.invalid", password, 0, it) })
                gate(model, GateStatus.NoAccount)
            }
            val uid = "p1_android_${if (action == "accept") "client" else "trainer"}"
            val setup = InvitationJson.format.parseToJsonElement(fixture(client, action, "setup")).jsonObject.getValue("request")
            val request = InvitationJson.format.decodeFromJsonElement(InvitationRequest.serializer(), setup)
            val ledger = InvitationLedger(AndroidAtomicFilePersistence(context))
            assertTrue(ledger.prepare(uid, request))
            val original = ledger.pending(uid)!!
            val protectedBefore = AndroidAtomicFilePersistence(context).read(uid)
            if (action == "accept") restoredEntry(client, model, uid, request)
            // Original invocation was committed by the host, but no reply reached this caller.
            for (fault in listOf("quota", "internal", "unauthorized", "conflict")) {
                if (fault != "quota") fixture(client, action, "mode", fault)
                val outcome = await<InvitationOutcome> { client.invitations.execute(uid, request, it) }
                assertTrue(outcome.ambiguous, "$action/$fault must not settle prior work")
                assertNull(outcome.reply)
                when (fault) {
                    "quota" -> assertEquals("rate-limited", outcome.error)
                    "internal" -> assertTrue(outcome.error in listOf("unavailable", "invitation-unavailable"))
                    "unauthorized" -> assertEquals("unauthorized", outcome.error)
                    "conflict" -> assertEquals("idempotency-key-reused", outcome.error)
                }
                assertEquals(protectedBefore, AndroidAtomicFilePersistence(context).read(uid))
                assertEquals(original, ledger.pending(uid))
                assertFalse(client.hasProvenEmptyCriticalWork(uid))
                main { model.onEvent(IdentityEvent.SignOut) }; gate(model, GateStatus.DepartureBlocked)
                assertEquals(StableFirebaseErrorCode.FAILED_PRECONDITION,
                    await<StableFirebaseFailure?> { client.signIn("p1-android-client@example.invalid", password, 0, it) }?.code)
                assertEquals(uid, FirebaseAuth.getInstance(client.firebaseApp).currentUser?.uid)
            }
            val root = if (action == "accept") guardedRoot(client, model, uid, request) else null
            fixture(client, action, "mode", "forward")
            if (root != null) {
                client.beforeInvitationTransportForTest = { issue -> assertEquals(original, client.invitations.pending(uid)); issue() }
                main { root.invitation.onEvent(InvitationEvent.RetryCommand) }
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
                while (root.invitation.currentState().busy && System.nanoTime() < end) Thread.sleep(30)
                assertTrue(root.invitation.currentState().message.startsWith("Invitation accepted"))
                assertNull(client.invitations.memory.link)
                main { root.clear() }
            } else {
                val recovered = await<InvitationOutcome> { client.invitations.execute(uid, request, it) }
                assertNull(recovered.error); assertFalse(recovered.ambiguous); assertTrue(recovered.reply!!.replayed!!)
            }
            assertNull(ledger.pending(uid)); assertTrue(client.hasProvenEmptyCriticalWork(uid))
            val evidence = InvitationJson.format.parseToJsonElement(fixture(client, action, "evidence")).jsonObject
            assertEquals("true", evidence.getValue("unchanged").jsonPrimitive.content)
            assertEquals("30", evidence.getValue("admissionQuota").jsonPrimitive.content)
            println("PR3_P1_ANDROID $action: four-error-ledger-and-departure-preservation, unchanged-receipt-replay PASS")
            main { model.onEvent(IdentityEvent.Retry) }
            gate(model, if (action == "accept") GateStatus.Onboarding else GateStatus.TrainerHome)
        }
        main { model.onEvent(IdentityEvent.SignOut) }; gate(model, GateStatus.SignedOut)
    }
}
