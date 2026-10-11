package com.delminiusapps.tillfailure.firebase

import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.delminiusapps.tillfailure.identity.GateStatus
import com.delminiusapps.tillfailure.identity.IdentityViewModel
import com.delminiusapps.tillfailure.identity.MembershipDiscoveryResult
import com.delminiusapps.tillfailure.identity.checkAccount
import com.delminiusapps.tillfailure.identity.checkMembership
import com.delminiusapps.tillfailure.identity.IdentityDecision
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Requires `npm run seed:pr1` against the local Auth and Firestore emulators. */
class AndroidProductIdentityTest {
    @Test fun pr2OperatorProvisioningChangesOnlyServerVerifiedTrainerRoot() {
        val args = InstrumentationRegistry.getArguments()
        val stage = args.getString("pr2Stage")
        assumeTrue("Run with pr2Stage=pending or verified against the PR 2 fixture", stage == "pending" || stage == "verified")
        val password = args.getString("pr2Password") ?: error("Pass -e pr2Password for the local emulator identity")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = AndroidFirebaseSpikeClient(context, emulatorConfiguration(), AccountCallbackFence(), productMemoryCache = true)
        try {
            if (stage == "pending") {
                assertNull(await<StableFirebaseFailure?> { done ->
                    client.signIn("pr2-trainer@example.invalid", password, 0L) { done(it) }
                })
            } else {
                assertEquals("pr2_trainer", FirebaseAuth.getInstance(client.firebaseApp).currentUser?.uid)
            }
            // Constructing the gate with the already signed-in native SDK exercises restoration.
            val model = IdentityViewModel(client)
            if (stage == "pending") {
                awaitStatus(model, GateStatus.WorkspaceGate)
                assertNull(model.state.value.verifiedRole)
                model.onEvent(com.delminiusapps.tillfailure.identity.IdentityEvent.Retry)
                awaitStatus(model, GateStatus.WorkspaceGate)
                assertEquals(StableFirebaseErrorCode.PERMISSION_DENIED,
                    await<FirebaseDocumentResult> { done ->
                        client.getDocument("workspaces/pr2_workspace/trainerProfiles/pr2_trainer", 0L, done)
                    }.failure?.code)
            } else {
                awaitStatus(model, GateStatus.TrainerHome)
                assertEquals("trainer", model.state.value.verifiedRole)
                assertEquals("pr2_workspace", model.state.value.verifiedWorkspaceId)
                val profile = await<FirebaseDocumentResult> { done ->
                    client.getDocument("workspaces/pr2_workspace/trainerProfiles/pr2_trainer", 0L, done)
                }
                assertNull(profile.failure)
                assertEquals("pr2_trainer", profile.document?.fields?.get("userId"))
            }
        } finally {
            val ended = CountDownLatch(1)
            client.terminateAndClear { ended.countDown() }
            ended.await(15, TimeUnit.SECONDS)
        }
    }

    @Test fun invalidPasswordIsClassifiedWithoutGrantingAnIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = AndroidFirebaseSpikeClient(context, emulatorConfiguration(), AccountCallbackFence())
        try {
            FirebaseAuth.getInstance(client.firebaseApp).signOut()
            val failure = await<StableFirebaseFailure?> { done ->
                client.signIn("pr1-client@example.invalid", "deliberately-invalid", 0L) { done(it) }
            }
            assertEquals(StableFirebaseErrorCode.INVALID_CREDENTIALS, failure?.code)
            assertNull(FirebaseAuth.getInstance(client.firebaseApp).currentUser)
        } finally {
            val ended = CountDownLatch(1)
            client.terminateAndClear { ended.countDown() }
            ended.await(15, TimeUnit.SECONDS)
        }
    }

    @Test fun seededEmailPasswordIdentityRequiresThreeServerProofs() {
        val password = InstrumentationRegistry.getArguments().getString("pr1Password")
            ?: error("Pass -e pr1Password for the local emulator identity")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = AndroidFirebaseSpikeClient(context, emulatorConfiguration(), AccountCallbackFence())
        try {
            val signIn = await<StableFirebaseFailure?> { done ->
                client.signIn("pr1-client@example.invalid", password, 0L) { done(it) }
            }
            assertNull(signIn)
            val account = await<FirebaseDocumentResult> { done -> client.getDocument("users/pr1_client", 0L, done) }.document
            assertNull(checkAccount("pr1_client", account))
            val discovery = await<MembershipDiscoveryResult> { done -> client.discoverMemberships("pr1_client", 0L, done) }
            assertNull(discovery.failure)
            assertEquals(1, discovery.documents?.size)
            assertEquals("users/pr1_client/membershipRefs/pr1_workspace", discovery.documents?.single()?.path)
            val workspace = await<FirebaseDocumentResult> { done -> client.getDocument("workspaces/pr1_workspace", 0L, done) }.document
            val membership = await<FirebaseDocumentResult> { done ->
                client.getDocument("workspaces/pr1_workspace/memberships/pr1_client", 0L, done)
            }.document
            assertEquals(IdentityDecision.Verified("pr1_workspace", "client"),
                checkMembership("pr1_client", "pr1_workspace", workspace, membership))
        } finally {
            val ended = CountDownLatch(1)
            client.terminateAndClear { ended.countDown() }
            ended.await(15, TimeUnit.SECONDS)
        }
    }

    @Test fun unverifiedEmailSessionReachesTheUnverifiedGateBeforeAuthorization() {
        val password = InstrumentationRegistry.getArguments().getString("pr1Password")
            ?: error("Pass -e pr1Password for the local emulator identity")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = AndroidFirebaseSpikeClient(context, emulatorConfiguration(), AccountCallbackFence())
        try {
            FirebaseAuth.getInstance(client.firebaseApp).signOut()
            val signIn = await<StableFirebaseFailure?> { done ->
                client.signIn("pr1-unverified@example.invalid", password, 0L) { done(it) }
            }
            assertNull(signIn)
            // The freshly refreshed session propagates the verification state from Auth; it is not a
            // cached observed flag and not the entered email.
            val refresh = await<FirebaseSessionRefresh> { done -> client.refreshSession(0L, done) }
            assertEquals("pr1-unverified@example.invalid", refresh.session?.email)
            assertEquals(false, refresh.session?.emailVerified)
            val observed = await<FirebaseAuthSession> { done ->
                client.observeSession(0L) { session -> done(session) }
            }
            assertEquals(false, observed.emailVerified)
            // The shared gate must stop before account/workspace/membership authorization.
            val model = IdentityViewModel(client)
            awaitStatus(model, GateStatus.UnverifiedEmail)
            assertNull(model.state.value.verifiedRole)
            // The UI gate is not the Rules boundary: the same signed-in SDK must independently
            // be denied workspace, exact membership and discovery reads.
            assertEquals(StableFirebaseErrorCode.PERMISSION_DENIED,
                await<FirebaseDocumentResult> { done -> client.getDocument("workspaces/pr1_workspace", 0L, done) }.failure?.code)
            assertEquals(StableFirebaseErrorCode.PERMISSION_DENIED,
                await<FirebaseDocumentResult> { done ->
                    client.getDocument("workspaces/pr1_workspace/memberships/pr1_unverified", 0L, done)
                }.failure?.code)
            assertEquals(StableFirebaseErrorCode.PERMISSION_DENIED,
                await<MembershipDiscoveryResult> { done -> client.discoverMemberships("pr1_unverified", 0L, done) }.failure?.code)
        } finally {
            val ended = CountDownLatch(1)
            client.terminateAndClear { ended.countDown() }
            ended.await(15, TimeUnit.SECONDS)
        }
    }

    @Test fun recheckObservesAnOutOfBandEmailVerificationWithoutSigningInAgain() {
        val password = InstrumentationRegistry.getArguments().getString("pr1Password")
            ?: error("Pass -e pr1Password for the local emulator identity")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = AndroidFirebaseSpikeClient(context, emulatorConfiguration(), AccountCallbackFence())
        try {
            assertTrue(setEmailVerified("pr1_unverified", false)) // deterministic fixture baseline
            FirebaseAuth.getInstance(client.firebaseApp).signOut()
            assertNull(await<StableFirebaseFailure?> { done ->
                client.signIn("pr1-unverified@example.invalid", password, 0L) { done(it) }
            })
            val stale = await<FirebaseSessionRefresh> { done -> client.refreshSession(0L, done) }
            assertEquals(false, stale.session?.emailVerified)
            // The account is verified out of band while this client stays signed in.
            assertTrue(setEmailVerified("pr1_unverified", true))
            // Recheck must observe the fresh state from a forced refresh, not a cached profile.
            val refreshed = await<FirebaseSessionRefresh> { done -> client.refreshSession(0L, done) }
            assertEquals(true, refreshed.session?.emailVerified)
            assertEquals("pr1-unverified@example.invalid", refreshed.session?.email)
            // The shared gate now authorizes past the unverified gate without another sign-in.
            val model = IdentityViewModel(client)
            awaitStatus(model, GateStatus.MembershipVerifiedFeaturePending)
        } finally {
            setEmailVerified("pr1_unverified", false)
            val ended = CountDownLatch(1)
            client.terminateAndClear { ended.countDown() }
            ended.await(15, TimeUnit.SECONDS)
        }
    }

    /** Out-of-band Admin change against the local Auth emulator, as a support/verification flow would. */
    private fun setEmailVerified(uid: String, verified: Boolean): Boolean {
        val connection = URL(
            "http://10.0.2.2:${emulatorConfiguration().authPort}/identitytoolkit.googleapis.com/v1/projects/demo-tillfailure-m3/accounts:update"
        ).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer owner")
            connection.outputStream.use { it.write("""{"localId":"$uid","emailVerified":$verified}""".toByteArray()) }
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }

    private fun emulatorConfiguration(): FirebaseEmulatorConfiguration {
        val arguments = InstrumentationRegistry.getArguments()
        return FirebaseEmulatorConfiguration(
            host = "10.0.2.2",
            authPort = arguments.getString("authPort")?.toIntOrNull() ?: 9099,
            firestorePort = arguments.getString("firestorePort")?.toIntOrNull() ?: 8080,
        )
    }

    private fun awaitStatus(model: IdentityViewModel, status: GateStatus, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (model.state.value.status == status) return
            Thread.sleep(50)
        }
        assertEquals(status, model.state.value.status)
    }

    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val result = AtomicReference<T>()
        val done = CountDownLatch(1)
        start { result.set(it); done.countDown() }
        check(done.await(15, TimeUnit.SECONDS)) { "Firebase emulator callback timed out" }
        return result.get()
    }
}
