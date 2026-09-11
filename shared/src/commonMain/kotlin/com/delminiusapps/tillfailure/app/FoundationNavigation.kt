package com.delminiusapps.tillfailure.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.delminiusapps.tillfailure.foundation.details.FoundationDetailsRoute
import com.delminiusapps.tillfailure.foundation.home.FoundationHomeRoute
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable
sealed interface FoundationDestination : NavKey

@Serializable
data object FoundationHome : FoundationDestination

@Serializable
data object FoundationDetails : FoundationDestination

private val foundationSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(FoundationHome::class, FoundationHome.serializer())
            subclass(FoundationDetails::class, FoundationDetails.serializer())
        }
    }
}

@Composable
internal fun FoundationNavigation() {
    val backStack = rememberNavBackStack(
        foundationSavedStateConfiguration,
        FoundationHome,
    )
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        NavDisplay(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            backStack = backStack,
            onBack = {
                if (backStack.size > 1) {
                    backStack.removeLastOrNull()
                }
            },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<FoundationHome> {
                    FoundationHomeRoute(
                        onNavigateToDetails = {
                            if (backStack.lastOrNull() != FoundationDetails) {
                                backStack.add(FoundationDetails)
                            }
                        },
                        onShowMessage = snackbarHostState::showSnackbar,
                    )
                }
                entry<FoundationDetails> {
                    FoundationDetailsRoute(
                        onNavigateBack = {
                            if (backStack.size > 1) {
                                backStack.removeLastOrNull()
                            }
                        },
                    )
                }
            },
        )
    }
}
