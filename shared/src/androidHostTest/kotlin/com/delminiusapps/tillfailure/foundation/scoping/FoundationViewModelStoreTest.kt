package com.delminiusapps.tillfailure.foundation.scoping

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.delminiusapps.tillfailure.foundation.details.FoundationDetailsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FoundationViewModelStoreTest {
    @Test
    fun clearingPoppedDestinationStoreReleasesViewModelAndFreshStoreCreatesNewInstance() {
        val probe = FoundationScopeProbe()
        val factory = viewModelFactory {
            initializer { FoundationDetailsViewModel(probe) }
        }
        val firstStore = ViewModelStore()
        val firstProvider = ViewModelProvider.create(firstStore, factory)

        val first = firstProvider[FoundationDetailsViewModel::class]
        assertSame(first, firstProvider[FoundationDetailsViewModel::class])
        assertEquals(1, first.state.value.instanceNumber)

        firstStore.clear()
        assertEquals(1, probe.snapshot().releasedDetailsCount)

        val secondStore = ViewModelStore()
        val second = ViewModelProvider.create(secondStore, factory)[FoundationDetailsViewModel::class]
        assertEquals(2, second.state.value.instanceNumber)
        assertEquals(1, second.state.value.previouslyReleasedCount)

        secondStore.clear()
        assertEquals(2, probe.snapshot().releasedDetailsCount)
    }
}
