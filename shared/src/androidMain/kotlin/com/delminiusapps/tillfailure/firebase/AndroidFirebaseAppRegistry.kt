package com.delminiusapps.tillfailure.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Owns the process-wide named `FirebaseApp` instances used by the spike clients.
 *
 * A Firestore client that completed `terminateAndClear` is permanently unusable, and
 * `FirebaseFirestore.getInstance(app)` keeps handing back that same terminated instance for as long
 * as the app object exists. The registry therefore issues one *generation* per project at a time:
 *
 *  - a generation is closed synchronously the moment teardown starts, so a client constructed
 *    concurrently with (or after) teardown can never acquire the dying app or its Firestore/Auth
 *    singletons;
 *  - the next client acquires a brand-new app with a unique name, so it always receives fresh SDK
 *    instances, independent of when the retired app's cleanup finishes;
 *  - the retired app is deliberately **not** deleted: `FirebaseApp.delete()` makes every later
 *    by-name SDK lookup of components that still reference it fail with
 *    `IllegalStateException: FirebaseApp was deleted` — observed even in a *new* client's
 *    `FirebaseAuth.useEmulator()` call — so deleting is unsafe while any SDK component of the
 *    retired app may still be running. Closing the generation and never reissuing its instances is
 *    what guarantees a usable replacement; the retired app therefore stays as inert, unreferenced
 *    state instead of being destroyed underneath live components;
 *  - an old client keeps its own (dead) references, so it can never operate on a newer generation.
 */
internal object AndroidFirebaseAppRegistry {
    private val lock = Any()
    private val generations = HashMap<String, Generation>()
    private var sequence = 0L

    internal class Generation internal constructor(
        val projectId: String,
        val appName: String,
        val app: FirebaseApp,
    ) {
        @Volatile
        private var closed = false
        private var emulatorApplied = false

        internal val isClosed: Boolean get() = closed

        internal fun close() {
            closed = true
        }

        /**
         * Emulator endpoints can be applied to an Auth/Firestore instance only once, and two live
         * clients for the same project share a generation, so exactly one of them applies the
         * settings; later clients reuse the already-configured instances.
         */
        internal fun claimEmulatorConfiguration(): Boolean = synchronized(this) {
            if (emulatorApplied) return false
            emulatorApplied = true
            true
        }
    }

    /** Returns the live generation for the project, or initializes a fresh one. */
    fun acquire(context: Context, projectId: String): Generation = synchronized(lock) {
        val live = generations[projectId]
        if (live != null && !live.isClosed) return live
        sequence += 1
        val appName = "tillfailure-$projectId-$sequence"
        val options = FirebaseOptions.Builder()
            .setProjectId(projectId)
            .setApplicationId("1:1234567890:android:0000000000000000")
            .setApiKey("fake-emulator-api-key")
            .setStorageBucket("$projectId.appspot.com")
            .build()
        val app = requireNotNull(FirebaseApp.initializeApp(context.applicationContext, options, appName)) {
            "Failed to initialize the Firebase spike app $appName"
        }
        Generation(projectId, appName, app).also { generations[projectId] = it }
    }

    /**
     * Closes the generation synchronously so no new client can be handed its instances. This runs
     * before the SDK teardown starts and is idempotent.
     *
     * The retired app is intentionally left alive: see the class documentation — Android's
     * `FirebaseApp.delete()` breaks unrelated by-name component lookups while the retired app's Auth
     * or Firestore internals may still be finishing, and closing the generation is what guarantees
     * that a fresh client can never be handed the dead instances.
     */
    fun close(generation: Generation) {
        synchronized(lock) {
            generation.close()
            if (generations[generation.projectId] === generation) generations.remove(generation.projectId)
        }
    }
}
