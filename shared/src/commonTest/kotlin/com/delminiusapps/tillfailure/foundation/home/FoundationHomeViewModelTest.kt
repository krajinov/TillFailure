package com.delminiusapps.tillfailure.foundation.home

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FoundationHomeViewModelTest {
    @Test
    fun initialStateHasNoReps() {
        val viewModel = FoundationHomeViewModel()

        assertEquals(FoundationHomeState(), viewModel.state.value)
    }

    @Test
    fun addRepEventUpdatesState() {
        val viewModel = FoundationHomeViewModel()

        viewModel.onEvent(FoundationHomeEvent.OnAddRepClick)
        viewModel.onEvent(FoundationHomeEvent.OnAddRepClick)

        assertEquals(2, viewModel.state.value.repCount)
    }

    @Test
    fun openDetailsEventEmitsOneNavigationEffect() = runTest {
        val viewModel = FoundationHomeViewModel()

        viewModel.effect.test {
            viewModel.onEvent(FoundationHomeEvent.OnOpenDetailsClick)

            assertEquals(FoundationHomeEffect.NavigateToDetails, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun openCatalogEventEmitsOneNavigationEffect() = runTest {
        val viewModel = FoundationHomeViewModel()

        viewModel.effect.test {
            viewModel.onEvent(FoundationHomeEvent.OnOpenDesignCatalogClick)

            assertEquals(FoundationHomeEffect.NavigateToDesignCatalog, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun messageEventEmitsTransientMessage() = runTest {
        val viewModel = FoundationHomeViewModel()

        viewModel.effect.test {
            viewModel.onEvent(FoundationHomeEvent.OnShowMessageClick)

            assertEquals(
                FoundationHomeEffect.ShowMessage("Foundation effect received"),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
