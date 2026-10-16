package com.delminiusapps.tillfailure.firebase

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.delminiusapps.tillfailure.identity.*
import com.delminiusapps.tillfailure.invitations.*
import com.delminiusapps.tillfailure.onboarding.*
import com.delminiusapps.tillfailure.persistence.AndroidAtomicFilePersistence
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import kotlin.test.*

/** Opt-in, isolated PR 4 fixtures on PR 5's dedicated loopback demo services. Never clears data. */
class AndroidRecoveryHardeningTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val uid = "pr5_android_native"
    private val wid = "pr5_android_workspace"
    private val context: Context get() = object : ContextWrapper(instrumentation.targetContext) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("pr5_verified_$name", mode)
        override fun getNoBackupFilesDir() = java.io.File(super.getNoBackupFilesDir(), "pr5-verified").apply { mkdirs() }
    }
    private fun scenarioContext(name: String) = object : ContextWrapper(context) {
        override fun getSharedPreferences(key: String, mode: Int) = super.getSharedPreferences("${name}_$key", mode)
        override fun getNoBackupFilesDir() = java.io.File(super.getNoBackupFilesDir(), name).apply { mkdirs() }
    }
    private fun fresh(protectedContext: Context = context) = AndroidFirebaseSpikeClient(instrumentation.targetContext,
        FirebaseEmulatorConfiguration(host = "10.0.2.2", authPort = 39099, firestorePort = 38080, functionsPort = 35001),
        AccountCallbackFence(), productMemoryCache = true, protectedContextForTest = protectedContext)
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun completion(targetWorkspace: String = wid) = InvitationRequest("complete-onboarding", workspaceId = targetWorkspace, idempotencyKey = "pr5-original",
        onboarding = OnboardingCompletion("Recovery Client", "en", "UTC", true, 0, 0))
    private fun signIn(client: AndroidFirebaseSpikeClient) {
        val password = InstrumentationRegistry.getArguments().getString("pr5Password") ?: error("Pass pr5Password")
        if (com.google.firebase.auth.FirebaseAuth.getInstance(client.firebaseApp).currentUser?.uid != uid)
            assertNull(await<StableFirebaseFailure?> { client.signIn("pr5-android-native@example.invalid", password, 0, it) })
        if (!client.hasPinnedIdentity()) {
            assertEquals(StableFirebaseErrorCode.FAILED_PRECONDITION, await<StableFirebaseFailure?> {
                client.signIn("pr5-android-trainer@example.invalid", password, 0, it)
            }?.code)
            assertEquals(uid, com.google.firebase.auth.FirebaseAuth.getInstance(client.firebaseApp).currentUser?.uid)
        }
        assertTrue(client.claimPinnedIdentity(uid))
    }

    @Test fun revocationInFlightRevalidationRetainsOriginalUntilAuthorizedReplayThenIsolatesB() {
        val client = fresh(); signIn(client)
        patch("workspaces/$wid/memberships/$uid", "status", "active") // Restore only this test's authority fixture after a failed attempt.
        var root: NativeOnboardingModels? = null
        main { root = NativeOnboardingModels(client) }
        until { root!!.identity.currentState().status in setOf(GateStatus.Onboarding, GateStatus.ClientHome) }
        // An earlier failed probe may already have committed this exact original command. Resume
        // against its server state and receipt; never reset the profile or replace its key.
        val restoredStatus = root!!.identity.currentState().status
        val request = completion()
        var transport: (() -> Unit)? = null
        client.beforeInvitationTransportForTest = { transport = it }
        val denied = AtomicReference<InvitationOutcome>()
        main { client.invitations.execute(uid, request) { denied.set(it) } }
        until { transport != null }
        assertEquals(request.pending(uid), client.invitations.pending(uid))
        patch("workspaces/$wid/memberships/$uid", "status", "revoked")
        // Explicit reconnect stimulus also makes this probe independent of the emulator's failed
        // watch-notification stream. The subsequent server denial, not connectivity, is authority.
        reconnect(client, root!!.identity)
        gate(root!!.identity, GateStatus.LockedRecovery)
        assertNull(root!!.identity.currentState().verifiedRole)
        val rejected = CountDownLatch(1)
        client.beforeInvitationCompletionForTest = { done -> done(); rejected.countDown() }
        main { transport!!() }; assertTrue(rejected.await(40, TimeUnit.SECONDS))
        assertNotNull(denied.get().error)
        assertTrue(denied.get().ambiguous)
        assertNull(denied.get().reply)
        assertEquals(request.pending(uid), client.invitations.pending(uid))
        main { root!!.identity.onEvent(IdentityEvent.SignOut) }
        gate(root!!.identity, GateStatus.LockedRecovery)
        assertEquals(uid, client.firebaseApp.let { com.google.firebase.auth.FirebaseAuth.getInstance(it).currentUser?.uid })
        main { root!!.clear(); root = NativeOnboardingModels(client) }
        gate(root!!.identity, GateStatus.LockedRecovery)
        patch("workspaces/$wid/memberships/$uid", "status", "active")
        client.beforeInvitationTransportForTest = null; client.beforeInvitationCompletionForTest = null
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(client.firebaseApp)
        com.google.android.gms.tasks.Tasks.await(firestore.disableNetwork(), 10, TimeUnit.SECONDS)
        main { root!!.identity.onEvent(IdentityEvent.Connectivity(false)) }
        assertEquals(GateStatus.LockedRecovery, root!!.identity.currentState().status)
        com.google.android.gms.tasks.Tasks.await(firestore.enableNetwork(), 10, TimeUnit.SECONDS)
        main { root!!.identity.onEvent(IdentityEvent.Connectivity(true)) }; gate(root!!.identity, restoredStatus)
        assertEquals(request.pending(uid), client.invitations.pending(uid))
        val reply = await<InvitationOutcome> { client.invitations.execute(uid, request, it) }
        assertEquals("complete", reply.reply?.status); assertNull(client.invitations.pending(uid))
        if (restoredStatus == GateStatus.ClientHome) assertEquals(true, reply.reply?.replayed)
        else assertNotNull(reply.reply?.replayed) // A prior invocation can commit after the pending profile read.
        main { root!!.identity.onEvent(IdentityEvent.Retry) }; gate(root!!.identity, GateStatus.ClientHome)
        main { root!!.identity.onEvent(IdentityEvent.Suspend) }; assertNull(root!!.identity.currentState().verifiedRole)
        main { root!!.identity.onEvent(IdentityEvent.Foreground) }; gate(root!!.identity, GateStatus.ClientHome)
        patch("users/$uid", "accountStatus", "disabled")
        reconnect(client, root!!.identity)
        until { root!!.identity.currentState().status in setOf(GateStatus.AccessLost, GateStatus.AccountDisabled) }
        assertNull(root!!.identity.currentState().verifiedRole)
        main { root!!.identity.onEvent(IdentityEvent.SignOut) }; gate(root!!.identity, GateStatus.SignedOut)
        assertNull(AndroidAtomicFilePersistence(context).read(uid))
        val b = root!!.identity.activeClient()
        val password = InstrumentationRegistry.getArguments().getString("pr5Password")!!
        assertNull(await<StableFirebaseFailure?> { b.signIn("pr5-android-trainer@example.invalid", password, 0, it) })
        gate(root!!.identity, GateStatus.TrainerHome)
        assertEquals("pr5_android_trainer", root!!.identity.currentState().accountUid)
        assertEquals(StableFirebaseErrorCode.PERMISSION_DENIED, await<FirebaseDocumentResult> { b.getDocument("users/$uid", 0, it) }.failure?.code)
        main { root!!.identity.onEvent(IdentityEvent.SignOut) }; gate(root!!.identity, GateStatus.SignedOut)
        main { root!!.clear() }
    }

    @Test fun unauthenticatedAndRetiredClientsCannotCreateProtectedStateOrDeliverDisposedConnectivity() {
        val guarded = scenarioContext("final-review-guards")
        val client = fresh(guarded)
        val store = AndroidAtomicFilePersistence(guarded)
        val foreignUid = "pr5_final_review_foreign"
        assertNull(com.google.firebase.auth.FirebaseAuth.getInstance(client.firebaseApp).currentUser)
        assertFalse(client.claimPinnedIdentity(foreignUid))
        client.invitations.saveWorkspacePreference(foreignUid, wid)
        assertNull(store.read(foreignUid))
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        main {
            val observer = client.observeConnectivity { callbacks.incrementAndGet() }
            observer.cancel(); observer.cancel()
        }
        instrumentation.waitForIdleSync()
        assertEquals(0, callbacks.get())
        assertNull(await<StableFirebaseFailure?> { client.retireFirestore(it) })
        assertFalse(client.claimPinnedIdentity(foreignUid))
        client.invitations.saveWorkspacePreference(foreignUid, wid)
        main { client.observeConnectivity { callbacks.incrementAndGet() }.cancel() }
        instrumentation.waitForIdleSync()
        assertEquals(0, callbacks.get())
        assertNull(store.read(foreignUid))
        assertFalse(client.hasPinnedIdentity())
    }

    @Test fun frozenAccountCannotRewritePreferenceOrReinitializeProtectedState() {
        val guarded = scenarioContext("final-review-frozen")
        val client = fresh(guarded)
        val owner = "pr5_final_review_android"
        val password = InstrumentationRegistry.getArguments().getString("pr5Password") ?: error("Pass pr5Password")
        if (com.google.firebase.auth.FirebaseAuth.getInstance(client.firebaseApp).currentUser?.uid != owner)
            assertNull(await<StableFirebaseFailure?> { client.signIn("pr5-final-review-android@example.invalid", password, 0, it) })
        assertTrue(client.claimPinnedIdentity(owner))
        val request = completion()
        client.beforeInvitationTransportForTest = {} // Prepare durably; issue no remote mutation.
        main { client.invitations.execute(owner, request) {} }
        assertEquals(request.pending(owner), client.invitations.pending(owner))
        val file = AndroidAtomicFilePersistence(guarded).fileFor(owner)
        val before = file.readBytes()
        assertTrue(client.freeze(owner))
        client.invitations.saveWorkspacePreference(owner, "other")
        assertFalse(client.claimPinnedIdentity(owner))
        assertFalse(client.claimPinnedIdentity("foreign"))
        assertContentEquals(before, file.readBytes())
        assertEquals(request.pending(owner), client.invitations.pending(owner))
        client.unfreeze(owner)
        client.invitations.saveWorkspacePreference(owner, wid)
        assertEquals(wid, client.invitations.workspacePreference(owner))
        assertEquals(request.pending(owner), client.invitations.pending(owner))
        assertFalse(client.hasProvenEmptyCriticalWork(owner))
        // The test deliberately retains its original protected command and Auth owner.
    }

    @Test fun damagedStorageLocksTheRootAndPreservesBytes() {
        val damagedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("damage_$name", mode)
            override fun getNoBackupFilesDir() = java.io.File(super.getNoBackupFilesDir(), "damage").apply { mkdirs() }
        }
        val client = fresh(damagedContext); signIn(client)
        val store = AndroidAtomicFilePersistence(damagedContext)
        val file = store.fileFor(uid)
        file.writeText("{partial-protected-fixture")
        val damaged = file.readBytes()
        var model: IdentityViewModel? = null
        main { model = IdentityViewModel(client) }
        gate(model!!, GateStatus.LockedRecovery)
        assertEquals(ProtectedRecoveryState.StorageLocked, model!!.currentState().recovery)
        assertFalse(model!!.currentState().canSignOut)
        assertFalse(client.cleanupLocal(uid))
        assertContentEquals(damaged, file.readBytes())
        assertEquals(uid, com.google.firebase.auth.FirebaseAuth.getInstance(client.firebaseApp).currentUser?.uid)
    }

    @Test fun processBoundary() {
        val phase = InstrumentationRegistry.getArguments().getString("pr5Phase") ?: error("Pass pr5Phase=start or restore")
        val client = fresh(scenarioContext("process"))
        val processUid = "pr5_android_ui"
        if (phase == "start") {
            val password = InstrumentationRegistry.getArguments().getString("pr5Password")!!
            assertNull(await<StableFirebaseFailure?> { client.signIn("pr5-android-ui@example.invalid", password, 0, it) })
            assertTrue(client.claimPinnedIdentity(processUid))
            val committed = CountDownLatch(1)
            client.beforeInvitationCompletionForTest = { committed.countDown() }
            client.invitations.execute(processUid, completion()) {}
            assertTrue(committed.await(40, TimeUnit.SECONDS))
            assertEquals(completion().pending(processUid), client.invitations.pending(processUid))
        } else {
            assertEquals("restore", phase)
            assertEquals(processUid, com.google.firebase.auth.FirebaseAuth.getInstance(client.firebaseApp).currentUser?.uid)
            assertEquals(completion().pending(processUid), client.invitations.pending(processUid))
            assertFalse(client.hasProvenEmptyCriticalWork(processUid))
            val replay = await<InvitationOutcome> { client.invitations.execute(processUid, completion(), it) }
            assertNull(replay.error, replay.toString())
            assertEquals(true, replay.reply?.replayed)
            assertTrue(client.hasProvenEmptyCriticalWork(processUid))
            assertEquals(DepartureOutcome.Clean, await<DepartureOutcome> { CleanDepartureCoordinator(client).depart(processUid, {}, it) })
        }
    }

    @Test fun workspaceSuspensionClosesAnOpenRoot() {
        val client = fresh(scenarioContext("suspension"))
        val password = InstrumentationRegistry.getArguments().getString("pr5Password")!!
        assertNull(await<StableFirebaseFailure?> { client.signIn("pr5-android-large@example.invalid", password, 0, it) })
        var root: NativeOnboardingModels? = null
        main { root = NativeOnboardingModels(client) }; gate(root!!.identity, GateStatus.Onboarding)
        patch("workspaces/$wid", "status", "suspended")
        reconnect(client, root!!.identity)
        gate(root!!.identity, GateStatus.AccessLost)
        assertNull(root!!.identity.currentState().verifiedRole)
        main { root!!.identity.onEvent(IdentityEvent.SignOut) }; gate(root!!.identity, GateStatus.SignedOut)
        main { root!!.clear() }
    }

    private fun reconnect(client: AndroidFirebaseSpikeClient, model: IdentityViewModel) {
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(client.firebaseApp)
        com.google.android.gms.tasks.Tasks.await(firestore.disableNetwork(), 10, TimeUnit.SECONDS)
        main { model.onEvent(IdentityEvent.Connectivity(false)) }
        assertNull(model.currentState().verifiedRole)
        com.google.android.gms.tasks.Tasks.await(firestore.enableNetwork(), 10, TimeUnit.SECONDS)
        main { model.onEvent(IdentityEvent.Connectivity(true)) }
    }

    private fun patch(path: String, field: String, value: String) {
        val connection = URL("http://10.0.2.2:38080/v1/projects/demo-tillfailure-m3/databases/(default)/documents/$path?updateMask.fieldPaths=$field").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PATCH"; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer owner") // Firestore Emulator Admin fixture injection only.
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("{\"fields\":{\"$field\":{\"stringValue\":\"$value\"}}}".toByteArray()) }
            val status = connection.responseCode
            (if (status == 200) connection.inputStream else connection.errorStream)?.close()
            // Emulator watch notification can fail after the write commits. Confirm server state;
            // never retry the mutation or infer success merely from a missing error callback.
            val read = URL("http://10.0.2.2:38080/v1/projects/demo-tillfailure-m3/databases/(default)/documents/$path").openConnection() as HttpURLConnection
            try {
                read.setRequestProperty("Authorization", "Bearer owner")
                assertEquals(200, read.responseCode)
                val fields = org.json.JSONObject(read.inputStream.bufferedReader().use { it.readText() }).getJSONObject("fields")
                assertEquals(value, fields.getJSONObject(field).getString("stringValue"))
            } finally { read.disconnect() }
        } finally { connection.disconnect() }
    }
    private fun gate(model: IdentityViewModel, status: GateStatus) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        while (model.currentState().status != status && System.nanoTime() < deadline) Thread.sleep(50)
        assertEquals(status, model.currentState().status, "Native gate did not reach the expected state")
    }
    private fun until(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        while (!done() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue(done(), "Native recovery condition timed out")
    }
    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val value = AtomicReference<T>(); val latch = CountDownLatch(1)
        start { value.set(it); latch.countDown() }; assertTrue(latch.await(40, TimeUnit.SECONDS)); return value.get()
    }
}
