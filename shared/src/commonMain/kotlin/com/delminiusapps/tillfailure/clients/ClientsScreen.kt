package com.delminiusapps.tillfailure.clients

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.delminiusapps.tillfailure.core.designsystem.*
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun ClientsRoute(repository: ClientRepository, scope: ClientScope, clientId: String?, onOpen: (String) -> Unit, onBack: () -> Unit, onAccessLost: () -> Unit) {
    val vm: ClientsViewModel = koinViewModel(parameters = { parametersOf(repository) })
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val open by rememberUpdatedState(onOpen)
    val back by rememberUpdatedState(onBack)
    val lost by rememberUpdatedState(onAccessLost)
    DisposableEffect(vm, scope, clientId, owner) {
        vm.bind(scope, clientId)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.clear() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); vm.clear() }
    }
    LaunchedEffect(vm, owner, scope, clientId) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.bind(scope, clientId)
            launch { vm.effect.collect { when (it) { is ClientsEffect.OpenDetail -> open(it.clientId); ClientsEffect.Back -> back(); ClientsEffect.AccessLost -> lost() } } }
        }
    }
    ClientsScreen(if (state.scope == scope) state else ClientsState(loading = true), clientId != null, vm::onEvent)
}

@Composable
internal fun ClientsScreen(state: ClientsState, isDetail: Boolean, onEvent: (ClientsEvent) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(TillFailureSpacing.xl), verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.lg)) {
        item { TillFailureSecondaryButton("Back", { onEvent(ClientsEvent.Back) }) }
        item { Text(if (isDetail) "Client details" else "Clients", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge, color = TillFailureTheme.colors.primaryText) }
        if (state.loading) item { Text("Loading…", color = TillFailureTheme.colors.secondaryText) }
        if (state.error != null) item {
            Text(when (state.error) { "invalid-cursor" -> "This page has expired. Refresh the roster."; "access-lost" -> "Access is no longer available."; else -> if (isDetail) "Client details are unavailable. Retry while connected." else if (state.paginationError) "The next page is unavailable. Your loaded clients are shown below." else "Clients are unavailable. Retry while connected." }, color = TillFailureTheme.colors.secondaryText)
            TillFailureSecondaryButton(if (state.error == "invalid-cursor") "Refresh roster" else "Retry", { onEvent(if (state.error == "invalid-cursor") ClientsEvent.Refresh else ClientsEvent.Retry) })
        }
        if (!isDetail) {
            items(state.items, key = { it.clientId }) { client ->
                TillFailureCard {
                    Column(Modifier.fillMaxWidth().padding(TillFailureSpacing.lg), verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm)) {
                        ClientLabels(client)
                        TillFailureSecondaryButton("View details", { onEvent(ClientsEvent.Open(client.clientId)) })
                    }
                }
            }
            if (!state.loading && state.error == null && state.items.isEmpty()) item { Text("No assigned active clients.", color = TillFailureTheme.colors.secondaryText) }
            if (state.nextCursor != null && state.error == null) item { TillFailurePrimaryButton("Load more", { onEvent(ClientsEvent.LoadMore) }, enabled = !state.loading) }
        } else state.detail?.let { client -> item { ClientLabels(client) } }
        item { TillFailureSecondaryButton("Refresh", { onEvent(ClientsEvent.Refresh) }, enabled = !state.loading) }
    }
}

@Composable
private fun ClientLabels(client: ClientRecord) {
    Text(client.displayName ?: "Name not provided yet", style = MaterialTheme.typography.titleLarge, color = TillFailureTheme.colors.primaryText)
    Text("Active relationship", color = TillFailureTheme.colors.secondaryText)
    Text(if (client.onboardingStatus == "complete") "Onboarding complete" else "Onboarding pending", color = TillFailureTheme.colors.secondaryText)
}
