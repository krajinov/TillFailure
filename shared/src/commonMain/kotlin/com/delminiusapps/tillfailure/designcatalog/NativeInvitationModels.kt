package com.delminiusapps.tillfailure.designcatalog

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.delminiusapps.tillfailure.identity.IdentityViewModel
import com.delminiusapps.tillfailure.identity.ProductIdentityClient
import com.delminiusapps.tillfailure.invitations.InvitationViewModel

/** Opt-in native harness owner. Uses actual lifecycle clearing; never substitutes a fake client. */
class NativeInvitationModels(client: ProductIdentityClient) {
    private val store = ViewModelStore()
    private val provider = ViewModelProvider.create(store, viewModelFactory {
        initializer { IdentityViewModel(client) }
        initializer { InvitationViewModel(client.invitations!!) }
    })
    val identity = provider[IdentityViewModel::class]
    val invitation = provider[InvitationViewModel::class]
    fun synchronize() {
        invitation.updateClient(identity.activeClient().invitations!!)
        invitation.updateIdentity(identity.currentState())
    }
    fun clear() = store.clear()
}
