package com.delminiusapps.tillfailure.foundation.details

import app.cash.turbine.test
import com.delminiusapps.tillfailure.foundation.scoping.FoundationScopeProbe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FoundationDetailsViewModelTest {
    @Test
    fun initialStateContainsScopeRegistration() {
        val viewModel = FoundationDetailsViewModel(FoundationScopeProbe())

        assertEquals(
            FoundationDetailsState(
                instanceNumber = 1,
                previouslyReleasedCount = 0,
            ),
            viewModel.state.value,
        )
    }

    @Test
    fun addRepEventUpdatesDestinationState() {
        val viewModel = FoundationDetailsViewModel(FoundationScopeProbe())

        viewModel.onEvent(FoundationDetailsEvent.OnAddRepClick)

        assertEquals(1, viewModel.state.value.detailRepCount)
    }

    @Test
    fun backEventEmitsOneNavigationEffect() = runTest {
        val viewModel = FoundationDetailsViewModel(FoundationScopeProbe())

        viewModel.effect.test {
            viewModel.onEvent(FoundationDetailsEvent.OnBackClick)

            assertEquals(FoundationDetailsEffect.NavigateBack, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
