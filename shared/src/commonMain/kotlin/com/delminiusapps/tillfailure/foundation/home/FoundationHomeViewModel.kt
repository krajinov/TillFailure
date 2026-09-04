package com.delminiusapps.tillfailure.foundation.home

import androidx.lifecycle.ViewModel
import com.delminiusapps.tillfailure.foundation.mvi.deliverEffect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class FoundationHomeViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(FoundationHomeState())
    val state: StateFlow<FoundationHomeState> = mutableState.asStateFlow()

    private val effectChannel = Channel<FoundationHomeEffect>(Channel.BUFFERED)
    val effect: Flow<FoundationHomeEffect> = effectChannel.receiveAsFlow()

    fun onEvent(event: FoundationHomeEvent) {
        when (event) {
            FoundationHomeEvent.OnAddRepClick -> {
                mutableState.value = mutableState.value.copy(
                    repCount = mutableState.value.repCount + 1,
                )
            }
            FoundationHomeEvent.OnOpenDetailsClick -> {
                deliverEffect(effectChannel, FoundationHomeEffect.NavigateToDetails)
            }
            FoundationHomeEvent.OnOpenDesignCatalogClick -> {
                deliverEffect(effectChannel, FoundationHomeEffect.NavigateToDesignCatalog)
            }
            FoundationHomeEvent.OnShowMessageClick -> {
                deliverEffect(
                    effectChannel,
                    FoundationHomeEffect.ShowMessage("Foundation effect received"),
                )
            }
        }
    }

    override fun onCleared() {
        effectChannel.close()
    }
}
