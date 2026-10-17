package com.delminiusapps.tillfailure.clients

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.delminiusapps.tillfailure.identity.IdentityViewModel
import com.delminiusapps.tillfailure.identity.ProductIdentityClient

/** Native verification wrapper; uses the real destination stores and production onCleared fences. */
class NativeClientsModels(client: ProductIdentityClient) {
    private val store = ViewModelStore()
    private val provider = ViewModelProvider.create(store, viewModelFactory {
        initializer { IdentityViewModel(client) }
        initializer { ClientsViewModel(requireNotNull(client.clients)) }
    })
    val identity = provider[IdentityViewModel::class]
    val roster = provider["roster", ClientsViewModel::class]
    val details = provider["detail", ClientsViewModel::class]
    fun clear() = store.clear()
}
