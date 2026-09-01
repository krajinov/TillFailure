package com.delminiusapps.tillfailure.foundation.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch

/**
 * Attempts immediate transient delivery, then suspends in the ViewModel scope if the bounded buffer
 * is temporarily full. A closed channel means the destination was disposed and the effect is dropped.
 * Effects are intentionally not durable or replayed after process death.
 */
internal fun <Effect> ViewModel.deliverEffect(
    channel: Channel<Effect>,
    effect: Effect,
) {
    val result = channel.trySend(effect)
    if (result.isFailure && !result.isClosed) {
        viewModelScope.launch {
            try {
                channel.send(effect)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: ClosedSendChannelException) {
                // Disposal won the race with the suspended send; the transient effect is dropped.
            }
        }
    }
}
