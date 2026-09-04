package com.delminiusapps.tillfailure.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoundationSnackbarHostTest {
    private val destinations: List<FoundationDestination> = listOf(
        FoundationHome,
        FoundationDetails,
        DesignCatalog,
        CatalogAuthentication,
        CatalogClientHome,
        CatalogActiveWorkout,
        CatalogTrainerDashboard,
        CatalogTrainerClientDetails,
    )

    @Test
    fun rootOwnsOnlyDestinationsWithoutNestedCatalogScaffolds() {
        listOf(FoundationHome, FoundationDetails, CatalogAuthentication).forEach { destination ->
            assertEquals(FoundationSnackbarHostOwner.Root, foundationSnackbarHostOwner(destination))
        }
        destinations.filterNot { it in listOf(FoundationHome, FoundationDetails, CatalogAuthentication) }
            .forEach { destination ->
                assertEquals(FoundationSnackbarHostOwner.Destination, foundationSnackbarHostOwner(destination))
            }
    }

    @Test
    fun exactlyOneNestedScaffoldOwnsTheSharedHostForEachCatalogDestination() {
        destinations
            .filter { foundationSnackbarHostOwner(it) == FoundationSnackbarHostOwner.Destination }
            .forEach { activeDestination ->
                val owners = destinations.filter { candidate ->
                    destinationOwnsFoundationSnackbarHost(activeDestination, candidate)
                }

                assertEquals(listOf(activeDestination), owners)
                assertFalse(foundationSnackbarHostOwner(activeDestination) == FoundationSnackbarHostOwner.Root)
            }
    }

    @Test
    fun inactiveAndRootOwnedDestinationsDoNotCreateNestedHosts() {
        destinations.forEach { candidate ->
            assertFalse(destinationOwnsFoundationSnackbarHost(null, candidate))
            assertFalse(destinationOwnsFoundationSnackbarHost(FoundationHome, candidate))
        }
        assertTrue(foundationSnackbarHostOwner(null) == FoundationSnackbarHostOwner.Root)
    }
}
