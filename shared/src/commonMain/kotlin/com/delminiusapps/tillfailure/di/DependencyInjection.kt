package com.delminiusapps.tillfailure.di

import com.delminiusapps.tillfailure.clients.ClientRepository
import com.delminiusapps.tillfailure.clients.ClientsViewModel
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
    viewModel { (repository: com.delminiusapps.tillfailure.exercises.SystemExerciseRepository) -> com.delminiusapps.tillfailure.exercises.ExercisesViewModel(repository) }
    viewModel { (client: com.delminiusapps.tillfailure.invitations.InvitationClient) -> com.delminiusapps.tillfailure.clients.TrainerNoteViewModel(client) }
    viewModel { (client: com.delminiusapps.tillfailure.invitations.InvitationClient) ->
        com.delminiusapps.tillfailure.profile.ProfileViewModel(client) }
    viewModel { (repository: ClientRepository) ->
        ClientsViewModel(repository) }
    singleOf(::FoundationScopeProbe)
    viewModel { (client: com.delminiusapps.tillfailure.invitations.InvitationClient) ->
        com.delminiusapps.tillfailure.onboarding.OnboardingViewModel(client) }
    viewModelOf(::FoundationHomeViewModel)
    viewModelOf(::FoundationDetailsViewModel)
    viewModel { (client: ProductIdentityClient) -> IdentityViewModel(client) }
    viewModel { (client: com.delminiusapps.tillfailure.invitations.InvitationClient) ->
        com.delminiusapps.tillfailure.invitations.InvitationViewModel(client) }
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
