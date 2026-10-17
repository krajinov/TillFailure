package com.delminiusapps.tillfailure.clients

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.test
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClientsTest {
    private val a = ClientScope("a", "w", "a", 1)
    private val b = ClientScope("b", "other", "b", 2)
    private fun row(id: String, wid: String = "w") = ClientRecord(id, wid, "Name", "active", "pending")
    private class Repository : ClientRepository {
        val requests = mutableListOf<ClientRequest>()
        val callbacks = mutableListOf<(ClientResult) -> Unit>()
        var cancellations = 0
        override fun read(scope: ClientScope, request: ClientRequest, callback: (ClientResult) -> Unit): FirebaseCancellation {
            requests += request; callbacks += callback
            return FirebaseCancellation { cancellations++ }
        }
    }
    @BeforeTest fun dispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun reset() { Dispatchers.resetMain() }

    @Test fun pagesDeduplicateAndRetryOnlyFailedContinuationThenRefreshDropsOldProof() = runTest {
        val repository = Repository(); val vm = ClientsViewModel(repository)
        vm.bind(a, null); vm.onEvent(ClientsEvent.Refresh); assertEquals(1, repository.requests.size)
        repository.callbacks.last()(ClientResult(page = ClientPage(listOf(row("one"), row("two")), "cursor")))
        vm.onEvent(ClientsEvent.LoadMore); assertEquals("cursor", repository.requests.last().cursor)
        repository.callbacks.last()(ClientResult(error = "unavailable"))
        assertTrue(vm.state.value.paginationError); assertEquals(2, vm.state.value.items.size); assertEquals("cursor", vm.state.value.nextCursor)
        vm.onEvent(ClientsEvent.Retry); assertEquals("cursor", repository.requests.last().cursor)
        repository.callbacks.last()(ClientResult(page = ClientPage(listOf(row("two"), row("three")), null)))
        assertEquals(listOf("one", "two", "three"), vm.state.value.items.map { it.clientId }); assertNull(vm.state.value.nextCursor)
        vm.onEvent(ClientsEvent.Refresh); assertTrue(vm.state.value.items.isEmpty()); assertNull(repository.requests.last().cursor)
        vm.clear()
    }
    @Test fun lateRepliesAfterAccountWorkspaceEpochBackgroundOrRootDisposalCannotPublish() = runTest {
        for (next in listOf(b, a.copy(workspaceId = "new"), a.copy(authorityEpoch = 2))) {
            val repository = Repository(); val vm = ClientsViewModel(repository); vm.bind(a, null)
            val old = repository.callbacks.single(); vm.bind(next, null)
            old(ClientResult(page = ClientPage(listOf(row("secret")), "old")))
            assertEquals(next, vm.state.value.scope); assertTrue(vm.state.value.items.isEmpty()); assertNull(vm.state.value.nextCursor)
            vm.clear(); repository.callbacks.last()(ClientResult(detail = row("secret")))
            assertEquals(ClientsState(), vm.state.value); assertTrue(repository.cancellations >= 2)
        }
        val repository = Repository(); val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { ClientsViewModel(repository) } })[ClientsViewModel::class]
        vm.bind(a, "one"); val old = repository.callbacks.single(); store.clear(); old(ClientResult(detail = row("secret")))
        assertEquals(ClientsState(), vm.state.value)
        val fresh = ClientsViewModel(repository); fresh.bind(a, "one")
        assertNull(fresh.state.value.detail); assertEquals(2, repository.requests.size); fresh.clear()
    }
    @Test fun invalidCursorClearsPagesAndRetryStartsFreshWithoutClaimingExhaustion() = runTest {
        val repository = Repository(); val vm = ClientsViewModel(repository); vm.bind(a, null)
        repository.callbacks.last()(ClientResult(page = ClientPage(listOf(row("one")), "old")))
        vm.onEvent(ClientsEvent.LoadMore); repository.callbacks.last()(ClientResult(error = "invalid-cursor"))
        assertTrue(vm.state.value.items.isEmpty()); assertEquals("invalid-cursor", vm.state.value.error)
        vm.onEvent(ClientsEvent.Retry); assertNull(repository.requests.last().cursor); vm.clear()
    }
    @Test fun deniedDetailsClearProtectedPresentationAndRequestGateRevalidation() = runTest {
        val repository = Repository(); val vm = ClientsViewModel(repository); vm.bind(a, "one")
        repository.callbacks.last()(ClientResult(detail = row("one"))); assertNotNull(vm.state.value.detail)
        vm.onEvent(ClientsEvent.Refresh); assertNull(vm.state.value.detail)
        vm.effect.test {
            repository.callbacks.last()(ClientResult(error = "access-lost"))
            assertEquals(ClientsEffect.AccessLost, awaitItem()); assertEquals(ClientsState(), vm.state.value)
        }
        vm.clear()
    }
    @Test fun timeoutCancelsAndLateSuccessCannotOverwriteAvailability() = runTest {
        val repository = Repository(); val vm = ClientsViewModel(repository); vm.bind(a, null)
        advanceTimeBy(20_001); runCurrent()
        assertEquals("unavailable", vm.state.value.error)
        repository.callbacks.last()(ClientResult(page = ClientPage(listOf(row("late")), null)))
        assertEquals("unavailable", vm.state.value.error); assertTrue(vm.state.value.items.isEmpty()); vm.clear()
    }
    @Test fun queuedNavigationFromRetiredScopeDoesNotOpenAnotherAccountsDestination() = runTest {
        val repository = Repository(); val vm = ClientsViewModel(repository); vm.bind(a, null)
        repository.callbacks.last()(ClientResult(page = ClientPage(listOf(row("one")), null)))
        vm.onEvent(ClientsEvent.Open("one")); vm.bind(b, null)
        vm.effect.test { expectNoEvents(); cancelAndIgnoreRemainingEvents() }
        vm.clear()
    }
    @Test fun repeatedNavigationBeforeDestinationDisposalProducesOnlyOneEffect() = runTest {
        val repository = Repository(); val vm = ClientsViewModel(repository); vm.bind(a, null)
        repository.callbacks.last()(ClientResult(page = ClientPage(listOf(row("one")), null)))
        vm.onEvent(ClientsEvent.Open("missing"))
        vm.onEvent(ClientsEvent.Open("one")); vm.onEvent(ClientsEvent.Open("one")); vm.onEvent(ClientsEvent.Back)
        vm.effect.test {
            assertEquals(ClientsEffect.OpenDetail("one"), awaitItem()); expectNoEvents(); cancelAndIgnoreRemainingEvents()
        }
        vm.clear(); vm.bind(a, "one")
        vm.onEvent(ClientsEvent.Back); vm.onEvent(ClientsEvent.Back)
        vm.effect.test { assertEquals(ClientsEffect.Back, awaitItem()); expectNoEvents(); cancelAndIgnoreRemainingEvents() }
        vm.clear()
    }
    @Test fun minimalWireValidationDeniesExtraPrivateFieldsWrongScopeDamagedNamesAndBadContinuation() {
        val request = ClientRequest("list", "w", 2)
        val item = """{"clientId":"c","workspaceId":"w","displayName":null,"relationshipStatus":"active","onboardingStatus":"pending"}"""
        val body = """{"items":[$item],"nextCursor":null}"""
        assertNull(ClientJson.result(request, body, null).error)
        for (bad in listOf(body.replace("\"pending\"", "\"complete\""), body.replace("\"w\"", "\"foreign\""), body.replace("\"clientId\"", "\"email\":\"private\",\"clientId\""),
            body.replace("\"displayName\":null", "\"displayName\":\" bad \""), body.replace("\"displayName\":null", "\"displayName\":\"\\uD800\""),
            body.replace("\"nextCursor\":null", "\"nextCursor\":\"bad\""), body.replace("\"items\"", "\"hiddenCount\":1,\"items\"")))
            assertEquals("unavailable", ClientJson.result(request, bad, null).error)
        assertEquals("invalid-cursor", ClientJson.result(request, null, "invalid-cursor").error)
        assertEquals("access-lost", ClientJson.result(request, null, "unauthorized").error)
        assertFalse(ClientJson.valid(ClientRequest("search", "w")))
        assertFails { ClientScope("a", "w", "foreign", 1) }
    }
}
