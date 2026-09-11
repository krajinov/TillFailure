package com.delminiusapps.tillfailure.foundation.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun FoundationHomeRoute(
    onNavigateToDetails: () -> Unit,
    onShowMessage: suspend (String) -> Unit,
    viewModel: FoundationHomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effect.collect { effect ->
                when (effect) {
                    FoundationHomeEffect.NavigateToDetails -> onNavigateToDetails()
                    is FoundationHomeEffect.ShowMessage -> launch {
                        onShowMessage(effect.message)
                    }
                }
            }
        }
    }

    FoundationHomeScreen(
        state = state,
        onEvent = viewModel::onEvent,
    )
}
