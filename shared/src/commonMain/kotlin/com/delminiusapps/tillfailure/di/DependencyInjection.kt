package com.delminiusapps.tillfailure.di

import com.delminiusapps.tillfailure.foundation.details.FoundationDetailsViewModel
import com.delminiusapps.tillfailure.foundation.home.FoundationHomeViewModel
import com.delminiusapps.tillfailure.foundation.scoping.FoundationScopeProbe
import com.delminiusapps.tillfailure.identity.IdentityViewModel
import com.delminiusapps.tillfailure.identity.ProductIdentityClient
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

private val foundationModule = module {
    singleOf(::FoundationScopeProbe)
    viewModelOf(::FoundationHomeViewModel)
    viewModelOf(::FoundationDetailsViewModel)
    viewModel { (client: ProductIdentityClient) -> IdentityViewModel(client) }
}

private var isKoinInitialized = false

/** Starts the process-wide DI container once, outside Compose recomposition. */
fun initializeKoin() {
    if (!isKoinInitialized) {
        startKoin {
            modules(foundationModule)
        }
        isKoinInitialized = true
    }
}
