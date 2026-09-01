package com.delminiusapps.tillfailure.foundation.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun FoundationDetailsRoute(
    onNavigateBack: () -> Unit,
    viewModel: FoundationDetailsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effect.collect { effect ->
                when (effect) {
                    FoundationDetailsEffect.NavigateBack -> onNavigateBack()
                }
            }
        }
    }

    FoundationDetailsScreen(
        state = state,
        onEvent = viewModel::onEvent,
    )
}
