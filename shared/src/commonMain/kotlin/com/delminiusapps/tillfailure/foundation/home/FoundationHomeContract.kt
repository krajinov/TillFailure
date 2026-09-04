package com.delminiusapps.tillfailure.foundation.home

data class FoundationHomeState(
    val repCount: Int = 0,
)

sealed interface FoundationHomeEvent {
    data object OnAddRepClick : FoundationHomeEvent
    data object OnOpenDetailsClick : FoundationHomeEvent
    data object OnOpenDesignCatalogClick : FoundationHomeEvent
    data object OnShowMessageClick : FoundationHomeEvent
}

sealed interface FoundationHomeEffect {
    data object NavigateToDetails : FoundationHomeEffect
    data object NavigateToDesignCatalog : FoundationHomeEffect
    data class ShowMessage(val message: String) : FoundationHomeEffect
}
