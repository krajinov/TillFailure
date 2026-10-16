package com.delminiusapps.tillfailure.onboarding

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.delminiusapps.tillfailure.identity.IdentityViewModel
import com.delminiusapps.tillfailure.identity.ProductIdentityClient

/** Native verification owns a real root store; clear exercises production onCleared fencing. */
class NativeOnboardingModels(client: ProductIdentityClient) {
    private val store = ViewModelStore()
    private val provider = ViewModelProvider.create(store, viewModelFactory {
        initializer { IdentityViewModel(client) }
        initializer { OnboardingViewModel(requireNotNull(client.invitations)) }
    })
    val identity = provider[IdentityViewModel::class]
    val onboarding = provider[OnboardingViewModel::class]
    fun refresh() = onboarding.updateIdentity(identity.currentState())
    fun clear() = store.clear()
}
