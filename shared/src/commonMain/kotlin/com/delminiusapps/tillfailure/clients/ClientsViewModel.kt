package com.delminiusapps.tillfailure.clients

import com.delminiusapps.tillfailure.invitations.InvitationOutcome
import com.delminiusapps.tillfailure.invitations.InvitationJson
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ClientsState(val scope: ClientScope? = null, val items: List<ClientRecord> = emptyList(), val detail: ClientRecord? = null,
    val revocationPreparation: ClientRevocation? = null, val revocationPrompt: RevocationPrompt? = null,
    val revocationBusy: Boolean = false, val revocationMessage: String? = null,
    val loading: Boolean = false, val nextCursor: String? = null, val error: String? = null, val paginationError: Boolean = false) {
    override fun toString() = "ClientsState(redacted)"
}
data class RevocationPrompt(val scope: ClientScope, val clientUid: String, val generation: Long, val promptId: Long, val binding: ClientRevocation) { override fun toString() = "RevocationPrompt(redacted)" }
sealed interface ClientsEvent {
    data object Revoke : ClientsEvent
    data class ConfirmRevocation(val prompt: RevocationPrompt) : ClientsEvent
    data class CancelRevocation(val prompt: RevocationPrompt) : ClientsEvent
    data object Refresh : ClientsEvent
    data object LoadMore : ClientsEvent
    data object Retry : ClientsEvent
    data class Open(val clientId: String) : ClientsEvent
    data object Back : ClientsEvent
    data class Invitations(val compose: Boolean) : ClientsEvent
}
sealed interface ClientsEffect {
    data class Revoked(val scope: ClientScope, val clientUid: String, val generation: Long) : ClientsEffect
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
    private var promptSequence = 0L
    private var revocationCancellation: FirebaseCancellation? = null
    fun bind(scope: ClientScope, clientId: String?) {
        if (mutable.value.scope == scope && target == clientId) return
        clear(); target = clientId; mutable.value = ClientsState(scope = scope); load(false)
    }
    fun clear() {
        generation++; revocationCancellation?.cancel(); revocationCancellation = null; cancellation?.cancel(); cancellation = null; timeout?.cancel(); timeout = null
        mutable.value = ClientsState(); target = null; navigationPending = false
        while (effects.tryReceive().isSuccess) { /* Drop navigation from the retired authority. */ }
    }
    fun onEvent(event: ClientsEvent) {
        val state = mutable.value
        when (event) {
            ClientsEvent.Revoke -> {
                val scope=state.scope ?: return; val binding=state.revocationPreparation ?: return
                if (navigationPending || state.revocationBusy || state.loading || state.detail?.clientId != binding.clientUid) return
                mutable.value=state.copy(revocationPrompt=RevocationPrompt(scope,binding.clientUid,generation,++promptSequence,binding))
            }
            is ClientsEvent.CancelRevocation -> if (state.revocationPrompt == event.prompt && !state.revocationBusy) mutable.value=state.copy(revocationPrompt=null)
            is ClientsEvent.ConfirmRevocation -> revoke(event.prompt)

            ClientsEvent.Refresh -> if (!state.revocationBusy && state.revocationPrompt == null) load(false)
            ClientsEvent.LoadMore -> if (mutable.value.nextCursor != null) load(true)
            ClientsEvent.Retry -> load(mutable.value.paginationError)
            is ClientsEvent.Open -> if (!navigationPending && mutable.value.items.any { it.clientId == event.clientId }) {
                navigationPending = effects.trySend(ClientsEffect.OpenDetail(event.clientId)).isSuccess
            }
            ClientsEvent.Back -> if (!navigationPending) {
                navigationPending = effects.trySend(ClientsEffect.Back).isSuccess
                if (navigationPending) mutable.value=state.copy(revocationPrompt=null,revocationPreparation=null)
            }
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
                prepareRevocation(scope, result.detail.clientId, issued)
            } else if (target == null && result.page != null) {
                mutable.value = ClientsState(scope = scope,
                    items = ((if (more) old.items else emptyList()) + result.page.items).distinctBy { it.clientId }, nextCursor = result.page.nextCursor)
            } else mutable.value = mutable.value.copy(loading = false, error = "unavailable", paginationError = more)
        }
        timeout = viewModelScope.launch { delay(20_000); cancellation?.cancel(); complete(ClientResult(error = "unavailable")); generation++ }
        cancellation = repository.read(scope, request) { result -> viewModelScope.launch { complete(result) } }
    }
    private fun prepareRevocation(scope: ClientScope, clientUid: String, issued: Long) {
        val commands=repository.commands ?: return
        if (runCatching { commands.pending(scope.uid) }.getOrNull() != null) {
            mutable.value=mutable.value.copy(revocationMessage="An original command remains protected. Open Invitations and recovery."); return
        }
        val request=ClientRevocationProtocol.prepare(scope.workspaceId,clientUid)
        revocationCancellation=commands.execute(scope.uid,request) { outcome -> viewModelScope.launch {
            if (generation != issued || mutable.value.scope != scope || target != clientUid) return@launch
            val binding=outcome.reply?.revocationPreparation
            mutable.value=mutable.value.copy(revocationPreparation=if (outcome.error == null && outcome.reply?.let { InvitationJson.validReply(request,it) } == true) binding else null,
                revocationMessage=if (binding != null && outcome.error == null) null else if (outcome.error == "assignment-artifacts")
                    "Workspace access cannot be revoked while assignment records remain." else "Revocation is unavailable for this relationship. Refresh to check again.")
        } }
    }
    private fun revoke(prompt: RevocationPrompt) {
        val state=mutable.value; val commands=repository.commands ?: return
        if (navigationPending || state.revocationPrompt != prompt || state.scope != prompt.scope || generation != prompt.generation || target != prompt.clientUid ||
            state.detail?.clientId != prompt.clientUid || state.revocationPreparation != prompt.binding || state.revocationBusy) return
        val request=ClientRevocationProtocol.command(prompt.scope.workspaceId,prompt.binding,commands.newCommandKey())
        mutable.value=state.copy(revocationPrompt=null,revocationPreparation=null,revocationBusy=true,revocationMessage="Revocation outcome is pending. The original command remains protected.")
        revocationCancellation=commands.execute(prompt.scope.uid,request) { outcome -> viewModelScope.launch {
            if (generation != prompt.generation || mutable.value.scope != prompt.scope || target != prompt.clientUid) return@launch
            val valid=outcome.error == null && !outcome.ambiguous && outcome.reply?.let { InvitationJson.validReply(request,it) } == true
            val decision=outcome.reply?.revocationDecision
            if (valid && decision?.status == "client-revoked") {
                mutable.value=mutable.value.copy(detail=null,revocationBusy=false,revocationMessage="Client access was revoked. Refreshing the authorized roster.")
                effects.trySend(ClientsEffect.Revoked(prompt.scope,prompt.clientUid,generation))
            } else mutable.value=mutable.value.copy(revocationBusy=false,revocationMessage=if (valid && decision?.status == "revocation-conflict")
                "The relationship changed. The original request was not applied. Refresh and confirm again." else
                "The outcome is unconfirmed. Open Invitations and recovery to retry or resolve the original command. Nothing was discarded.")
        } }
    }
    fun consumeRevocationEffect(effect: ClientsEffect.Revoked, scope: ClientScope, clientId: String?): Boolean {
        if (mutable.value.scope != scope || effect.scope != scope || effect.generation != generation || effect.clientUid != clientId || target != clientId || mutable.value.detail != null) return false
        generation++; return true
    }
    override fun onCleared() { clear(); effects.close() }
}
