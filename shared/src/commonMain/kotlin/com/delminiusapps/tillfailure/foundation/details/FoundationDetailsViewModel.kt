package com.delminiusapps.tillfailure.foundation.details

import androidx.lifecycle.ViewModel
import com.delminiusapps.tillfailure.foundation.mvi.deliverEffect
import com.delminiusapps.tillfailure.foundation.scoping.FoundationScopeProbe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class FoundationDetailsViewModel(
    private val scopeProbe: FoundationScopeProbe,
) : ViewModel() {
    private val registration = scopeProbe.registerDetailsScope()
    private val mutableState = MutableStateFlow(
        FoundationDetailsState(
            instanceNumber = registration.instanceNumber,
            previouslyReleasedCount = registration.previouslyReleasedCount,
        ),
    )
    val state: StateFlow<FoundationDetailsState> = mutableState.asStateFlow()

    private val effectChannel = Channel<FoundationDetailsEffect>(Channel.BUFFERED)
    val effect: Flow<FoundationDetailsEffect> = effectChannel.receiveAsFlow()

    fun onEvent(event: FoundationDetailsEvent) {
        when (event) {
            FoundationDetailsEvent.OnAddRepClick -> {
                mutableState.value = mutableState.value.copy(
                    detailRepCount = mutableState.value.detailRepCount + 1,
                )
            }
            FoundationDetailsEvent.OnBackClick -> {
                deliverEffect(effectChannel, FoundationDetailsEffect.NavigateBack)
            }
        }
    }

    override fun onCleared() {
        effectChannel.close()
        scopeProbe.releaseDetailsScope()
    }
}
