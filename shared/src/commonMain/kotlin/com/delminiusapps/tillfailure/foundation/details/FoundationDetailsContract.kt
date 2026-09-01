package com.delminiusapps.tillfailure.foundation.details

data class FoundationDetailsState(
    val instanceNumber: Int,
    val previouslyReleasedCount: Int,
    val detailRepCount: Int = 0,
)

sealed interface FoundationDetailsEvent {
    data object OnAddRepClick : FoundationDetailsEvent
    data object OnBackClick : FoundationDetailsEvent
}

sealed interface FoundationDetailsEffect {
    data object NavigateBack : FoundationDetailsEffect
}
