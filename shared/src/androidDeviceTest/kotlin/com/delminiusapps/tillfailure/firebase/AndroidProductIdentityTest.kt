package com.delminiusapps.tillfailure.firebase

import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.delminiusapps.tillfailure.identity.MembershipDiscoveryResult
import com.delminiusapps.tillfailure.identity.checkAccount
import com.delminiusapps.tillfailure.identity.checkMembership
import com.delminiusapps.tillfailure.identity.IdentityDecision
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Requires `npm run seed:pr1` against the local Auth and Firestore emulators. */
class AndroidProductIdentityTest {
    @Test fun invalidPasswordIsClassifiedWithoutGrantingAnIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = AndroidFirebaseSpikeClient(context, FirebaseEmulatorConfiguration(host = "10.0.2.2"), AccountCallbackFence())
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
        val client = AndroidFirebaseSpikeClient(context, FirebaseEmulatorConfiguration(host = "10.0.2.2"), AccountCallbackFence())
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

    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val result = AtomicReference<T>()
        val done = CountDownLatch(1)
        start { result.set(it); done.countDown() }
        check(done.await(15, TimeUnit.SECONDS)) { "Firebase emulator callback timed out" }
        return result.get()
    }
}
