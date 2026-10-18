package com.delminiusapps.tillfailure.clients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ClientsState(val scope: ClientScope? = null, val items: List<ClientRecord> = emptyList(), val detail: ClientRecord? = null,
    val loading: Boolean = false, val nextCursor: String? = null, val error: String? = null, val paginationError: Boolean = false) {
    override fun toString() = "ClientsState(redacted)"
}
sealed interface ClientsEvent {
    data object Refresh : ClientsEvent
    data object LoadMore : ClientsEvent
    data object Retry : ClientsEvent
    data class Open(val clientId: String) : ClientsEvent
    data object Back : ClientsEvent
    data class Invitations(val compose: Boolean) : ClientsEvent
}
sealed interface ClientsEffect {
    data class OpenDetail(val clientId: String) : ClientsEffect
    data object Back : ClientsEffect
    data object AccessLost : ClientsEffect
    data class Invitations(val compose: Boolean) : ClientsEffect
}
/** Destination-owned, online-only presentation. Cancellation and sequence checks fence late replies. */
class ClientsViewModel(private val repository: ClientRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ClientsState())
    val state: StateFlow<ClientsState> = mutable.asStateFlow()
    fun currentState(): ClientsState = mutable.value
    private val effects = Channel<ClientsEffect>(Channel.BUFFERED)
    val effect = effects.receiveAsFlow()
    private var cancellation: FirebaseCancellation? = null
    private var timeout: Job? = null
    private var generation = 0L
    private var target: String? = null
    private var navigationPending = false
    fun bind(scope: ClientScope, clientId: String?) {
        if (mutable.value.scope == scope && target == clientId) return
        clear(); target = clientId; mutable.value = ClientsState(scope = scope); load(false)
    }
    fun clear() {
        generation++; cancellation?.cancel(); cancellation = null; timeout?.cancel(); timeout = null
        mutable.value = ClientsState(); target = null; navigationPending = false
        while (effects.tryReceive().isSuccess) { /* Drop navigation from the retired authority. */ }
    }
    fun onEvent(event: ClientsEvent) {
        when (event) {
            ClientsEvent.Refresh -> load(false)
            ClientsEvent.LoadMore -> if (mutable.value.nextCursor != null) load(true)
            ClientsEvent.Retry -> load(mutable.value.paginationError)
            is ClientsEvent.Open -> if (!navigationPending && mutable.value.items.any { it.clientId == event.clientId }) {
                navigationPending = effects.trySend(ClientsEffect.OpenDetail(event.clientId)).isSuccess
            }
            ClientsEvent.Back -> if (!navigationPending) navigationPending = effects.trySend(ClientsEffect.Back).isSuccess
            is ClientsEvent.Invitations -> if (!navigationPending && mutable.value.scope != null && target == null)
                navigationPending = effects.trySend(ClientsEffect.Invitations(event.compose)).isSuccess
        }
    }
    private fun load(more: Boolean) {
        val old = mutable.value; val scope = old.scope ?: return
        if (old.loading) return
        generation++; val issued = generation
        cancellation?.cancel(); timeout?.cancel()
        val request = if (target != null) ClientRequest("get", scope.workspaceId, clientId = target)
            else ClientRequest("list", scope.workspaceId, pageSize = 2, cursor = if (more) old.nextCursor else null)
        // Refresh revokes old presentation until the server has rechecked every target.
        mutable.value = if (more) old.copy(loading = true, error = null, paginationError = false)
            else ClientsState(scope = scope, loading = true)
        fun complete(result: ClientResult) {
            if (generation != issued || mutable.value.scope != scope) return
            timeout?.cancel(); cancellation = null
            if (result.error != null) {
                if (result.error == "access-lost") {
                    clear(); effects.trySend(ClientsEffect.AccessLost)
                } else if (result.error == "invalid-cursor") {
                    mutable.value = ClientsState(scope = scope, error = result.error)
                } else mutable.value = mutable.value.copy(loading = false, error = result.error, paginationError = more)
            } else if (target != null && result.detail != null) {
                mutable.value = ClientsState(scope = scope, detail = result.detail)
            } else if (target == null && result.page != null) {
                mutable.value = ClientsState(scope = scope,
                    items = ((if (more) old.items else emptyList()) + result.page.items).distinctBy { it.clientId }, nextCursor = result.page.nextCursor)
            } else mutable.value = mutable.value.copy(loading = false, error = "unavailable", paginationError = more)
        }
        timeout = viewModelScope.launch { delay(20_000); cancellation?.cancel(); complete(ClientResult(error = "unavailable")); generation++ }
        cancellation = repository.read(scope, request) { result -> viewModelScope.launch { complete(result) } }
    }
    override fun onCleared() { clear(); effects.close() }
}
