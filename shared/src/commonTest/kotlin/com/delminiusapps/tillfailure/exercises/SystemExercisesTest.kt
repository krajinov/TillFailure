package com.delminiusapps.tillfailure.exercises

import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.*
import com.delminiusapps.tillfailure.identity.*
import androidx.navigation3.runtime.NavKey

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SystemExercisesTest {
    private fun record(id: String, name: String = "Synthetic exercise", extra: String? = null) = buildJsonObject {
        put("id", id); put("data", buildJsonObject {
            put("schemaVersion", 1); put("name", name); put("nameNormalized", "synthetic exercise")
            put("muscleGroups", buildJsonArray { add("legs") }); put("equipment", buildJsonArray {})
            put("instructions", "Synthetic administration example.\nFollow the demonstration sequence."); put("status", "published")
            extra?.let { put(it, "unapproved") }
        })
    }
    private fun body(vararg records: JsonObject) = buildJsonObject { put("documents", JsonArray(records.toList())) }.toString()
    private fun row(id: String) = SystemExercise(id, id, id, emptyList(), emptyList(), "Text")
    @BeforeTest fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun cleanup() { Dispatchers.resetMain() }
    @Test fun validatesWholeLookaheadAndExactSchemaWithoutPartialSuccess() {
        val records = (1..21).map { record("ex_${it.toString().padStart(2, '0')}") }
        val page = SystemExerciseProtocol.result(null, null, body(*records.toTypedArray()), null)
        assertEquals(20, page.items.size); assertEquals("ex_20", page.nextId)
        val bad = records.dropLast(1) + record("ex_21", extra = "privateMetadata")
        val malformed = SystemExerciseProtocol.result(null, null, body(*bad.toTypedArray()), null)
        assertEquals(ExerciseError.Malformed, malformed.error); assertTrue(malformed.items.isEmpty()); assertNull(malformed.nextId)
        assertNull(SystemExerciseProtocol.result("ex_20", null, body(record("ex_21")), null).nextId)
        assertEquals(ExerciseError.Malformed, SystemExerciseProtocol.result("ex_20", null, body(record("ex_20")), null).error)
    }
    @Test fun rejectsTypesOrderingDuplicatesBoundsAndInvalidUnicode() {
        val valid = record("ex")
        val original = valid.getValue("data").jsonObject
        val changes = listOf("schemaVersion" to JsonPrimitive("1"), "schemaVersion" to JsonPrimitive(true),
            "status" to JsonPrimitive("archived"), "name" to JsonPrimitive("x".repeat(121)),
            "name" to JsonPrimitive("\uD800"), "instructions" to JsonPrimitive("x\rx"),
            "instructions" to JsonPrimitive("\u0000"), "instructions" to JsonPrimitive("x".repeat(2001)),
            "muscleGroups" to JsonArray(List(9) { JsonPrimitive("x$it") }),
            "equipment" to JsonArray(listOf(JsonPrimitive("same"), JsonPrimitive("same"))))
        for ((key, value) in changes) {
            val bad = buildJsonObject { put("id", "ex"); put("data", JsonObject(original + (key to value))) }
            assertEquals(ExerciseError.Malformed, SystemExerciseProtocol.result(null, null, body(bad), null).error, key)
        }
        for (records in listOf(arrayOf(record("b"), record("a")), arrayOf(valid, valid)))
            assertEquals(ExerciseError.Malformed, SystemExerciseProtocol.result(null, null, body(*records), null).error)
        val scalars = SystemExerciseProtocol.result(null, "ex", body(record("ex", "\uD83D\uDE00".repeat(120))), null)
        assertNotNull(scalars.detail)
        assertFalse(SystemExerciseProtocol.validId("../ex")); assertFalse(SystemExerciseProtocol.validRequest("uid", "a", "b"))
    }
    @Test fun missingDeniedUnavailableAndEmptyRemainDistinct() {
        assertEquals(ExerciseError.Missing, SystemExerciseProtocol.result(null, "ex", body(), null).error)
        assertEquals(ExerciseError.Denied, SystemExerciseProtocol.result(null, null, null, "PERMISSION_DENIED").error)
        assertEquals(ExerciseError.Unavailable, SystemExerciseProtocol.result(null, null, null, "UNAVAILABLE").error)
        assertNull(SystemExerciseProtocol.result(null, null, body(), null).error)
        assertEquals(ExerciseError.Malformed, SystemExerciseProtocol.result(null, "wrong", body(record("ex")), null).error)
    }
    @Test fun ownedDetailReturnRefreshesListAndOldSourceOrClientCannotNavigate() {
        val owner = ShellOwner("a", "w", "a", "trainer", 1)
        val home = ShellDestination(ShellPage.Home, owner = owner)
        val list = ShellDestination(ShellPage.Exercises, owner = owner, refreshVersion = 1)
        val detail = ShellDestination(ShellPage.ExerciseDetails, owner = owner, refreshVersion = 2, exerciseId = "ex")
        val stack = mutableListOf<NavKey>(home, list, detail)
        backFromExercises(stack, detail)
        assertEquals(list.copy(refreshVersion = 3), stack.last())
        backFromExercises(stack, detail); assertEquals(2, stack.size)
        openShellPage(stack, owner, ShellPage.Exercises, list); assertEquals(list.copy(refreshVersion = 3), stack.last())
        val client = owner.copy(trainer = null, role = "client"); val clientHome = home.copy(owner = client)
        val clientStack = mutableListOf<NavKey>(clientHome)
        openShellPage(clientStack, client, ShellPage.Exercises, clientHome); assertEquals(listOf<NavKey>(clientHome), clientStack)
    }
    private class Repository : SystemExerciseRepository {
        val requests = mutableListOf<Pair<String?, String?>>()
        val callbacks = mutableListOf<(ExerciseResult) -> Unit>()
        var cancels = 0
        override fun read(uid: String, afterId: String?, exerciseId: String?, callback: (ExerciseResult) -> Unit): FirebaseCancellation {
            requests += afterId to exerciseId; callbacks += callback; return FirebaseCancellation { cancels++ }
        }
    }
    private val owner = ExerciseVisit(ExerciseScope("a", "workspace", 1), 1)
    @Test fun paginationRetryDedupAndRefreshUseOnlyCurrentTransientContinuation() = runTest {
        val repo = Repository(); val vm = ExercisesViewModel(repo); vm.bind(owner)
        repo.callbacks.last()(ExerciseResult(items = listOf(row("a")), nextId = "a"))
        vm.onEvent(owner, ExercisesEvent.LoadMore); assertEquals("a", repo.requests.last().first)
        repo.callbacks.last()(ExerciseResult(error = ExerciseError.Malformed))
        assertEquals(listOf(row("a")), vm.state.value.items); assertTrue(vm.state.value.failedMore)
        vm.onEvent(owner, ExercisesEvent.Retry); assertEquals("a", repo.requests.last().first)
        repo.callbacks.last()(ExerciseResult(items = listOf(row("a"), row("b"))))
        assertEquals(listOf("a", "b"), vm.state.value.items.map { it.id }); assertNull(vm.state.value.nextId)
        vm.onEvent(owner, ExercisesEvent.Refresh); assertNull(repo.requests.last().first); assertTrue(vm.state.value.items.isEmpty())
        vm.clear()
    }
    @Test fun scopeBackgroundDisposalAndDuplicateLateRepliesCannotPublish() = runTest {
        for (next in listOf(owner.copy(scope = owner.scope.copy(uid = "b")), owner.copy(scope = owner.scope.copy(workspaceId = "other")), owner.copy(scope = owner.scope.copy(epoch = 2)), owner.copy(visit = 2))) {
            val repo = Repository(); val vm = ExercisesViewModel(repo); vm.bind(owner); val old = repo.callbacks.last()
            vm.bind(next); old(ExerciseResult(items = listOf(row("old"))))
            assertEquals(next, vm.state.value.owner); assertTrue(vm.state.value.items.isEmpty())
            vm.clear(owner); assertEquals(next, vm.state.value.owner)
            vm.suspend(next); repo.callbacks.last()(ExerciseResult(items = listOf(row("old"))))
            assertEquals(ExercisesState(), vm.state.value); vm.bind(next); assertEquals(2, repo.requests.size)
            vm.bind(next.copy(scope = next.scope.copy(epoch = 3))); assertEquals(3, repo.requests.size); vm.clear()
        }
    }
    @Test fun consumedNavigationAndTimeoutCannotReviveOldVisit() = runTest {
        val repo = Repository(); val vm = ExercisesViewModel(repo); vm.bind(owner)
        val original = repo.callbacks.last(); original(ExerciseResult(items = listOf(row("a"))))
        original(ExerciseResult(items = listOf(row("old")))); assertEquals("a", vm.state.value.items.single().id)
        vm.onEvent(owner, ExercisesEvent.Open("a")); val effect = vm.effect.first()
        vm.clear(); assertFalse(vm.consume(effect))
        vm.bind(owner.copy(visit = 2)); val late = repo.callbacks.last()
        advanceTimeBy(20_001); runCurrent(); assertEquals(ExerciseError.Unavailable, vm.state.value.error)
        late(ExerciseResult(items = listOf(row("late")))); assertTrue(vm.state.value.items.isEmpty()); vm.clear()
    }
}
