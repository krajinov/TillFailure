package com.delminiusapps.tillfailure.profile

import kotlinx.coroutines.flow.first
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.delminiusapps.tillfailure.identity.IdentityViewModel
import com.delminiusapps.tillfailure.identity.ProductIdentityClient

/** Native verification exercises the production owner and its disposal. */
class NativeProfileModels(private val client: ProductIdentityClient) {
    private val store = ViewModelStore()
    private val provider = ViewModelProvider.create(store, viewModelFactory {
        initializer { IdentityViewModel(client) }
        initializer { ProfileViewModel(requireNotNull(client.invitations)) }
    })
    val identity = provider[IdentityViewModel::class]
    val profile = provider[ProfileViewModel::class]
    fun refresh() = profile.update(requireNotNull(client.invitations), identity.currentState())
    suspend fun nextNavigation() = profile.effect.first()
    fun clear() = store.clear()
}
