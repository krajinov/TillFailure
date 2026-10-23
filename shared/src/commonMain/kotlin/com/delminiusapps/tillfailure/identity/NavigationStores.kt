package com.delminiusapps.tillfailure.identity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.defaultViewModelCreationExtras
import androidx.lifecycle.defaultViewModelProviderFactory
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreProvider
import androidx.lifecycle.viewmodel.navigation3.ViewModelStoreNavEntryDecorator

/** Some owners return fresh default extras on each read. Keep provider inputs for one owner stable
 * so a presentation-only recomposition cannot clear a destination's live command callback. */
@Composable
internal fun <T : Any> rememberProductViewModelStoreDecorator(): ViewModelStoreNavEntryDecorator<T> {
    val owner = checkNotNull(LocalViewModelStoreOwner.current)
    val extras = remember(owner) { owner.defaultViewModelCreationExtras }
    val factory = remember(owner) { owner.defaultViewModelProviderFactory }
    val provider = rememberViewModelStoreProvider(parent = owner, defaultCreationExtras = extras, defaultFactory = factory)
    return remember(provider) { ViewModelStoreNavEntryDecorator(provider) }
}
