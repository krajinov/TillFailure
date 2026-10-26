package com.delminiusapps.tillfailure.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delminiusapps.tillfailure.firebase.FirebaseCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ExercisesState(val owner: ExerciseVisit? = null, val items: List<SystemExercise> = emptyList(),
    val detail: SystemExercise? = null, val nextId: String? = null, val loading: Boolean = false,
    val error: ExerciseError? = null, val failedMore: Boolean = false)
sealed interface ExercisesEvent {
    data object Refresh : ExercisesEvent
    data object LoadMore : ExercisesEvent
    data object Retry : ExercisesEvent
    data class Open(val id: String) : ExercisesEvent
    data object Back : ExercisesEvent
}
data class ExercisesEffect(val owner: ExerciseVisit, val generation: Long, val id: String? = null)
class ExercisesViewModel(private val repository: SystemExerciseRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ExercisesState())
    val state = mutable.asStateFlow()
    private val effects = Channel<ExercisesEffect>(Channel.BUFFERED)
    val effect = effects.receiveAsFlow()
    private var generation = 0L
    private var cancellation: FirebaseCancellation? = null
    private var timeout: Job? = null
    private var pendingNavigation = false
    private var suspendedScope: ExerciseScope? = null
    fun bind(owner: ExerciseVisit) {
        if (owner.scope == suspendedScope || mutable.value.owner == owner) return
        clear(); mutable.value = ExercisesState(owner = owner); load(false)
    }
    fun clear(owner: ExerciseVisit? = null) {
        if (owner != null && mutable.value.owner != owner) return
        generation++; cancellation?.cancel(); cancellation = null; timeout?.cancel(); timeout = null
        pendingNavigation = false; mutable.value = ExercisesState()
        while (effects.tryReceive().isSuccess) { /* Retired navigation. */ }
    }
    fun suspend(owner: ExerciseVisit) {
        if (mutable.value.owner != owner) return
        suspendedScope = owner.scope; clear(owner)
    }
    fun onEvent(owner: ExerciseVisit, event: ExercisesEvent) {
        val current = mutable.value
        if (current.owner != owner || pendingNavigation) return
        when (event) {
            ExercisesEvent.Refresh -> load(false)
            ExercisesEvent.Retry -> load(current.failedMore)
            ExercisesEvent.LoadMore -> if (current.nextId != null && current.error == null) load(true)
            is ExercisesEvent.Open -> if (!current.loading && current.items.any { it.id == event.id }) navigate(owner, event.id)
            ExercisesEvent.Back -> navigate(owner, null)
        }
    }
    private fun navigate(owner: ExerciseVisit, id: String?) {
        generation++; cancellation?.cancel(); cancellation = null; timeout?.cancel(); timeout = null
        pendingNavigation = effects.trySend(ExercisesEffect(owner, generation, id)).isSuccess
    }
    fun consume(effect: ExercisesEffect): Boolean {
        if (!pendingNavigation || mutable.value.owner != effect.owner || generation != effect.generation) return false
        pendingNavigation = false; generation++
        return true
    }
    private fun load(more: Boolean) {
        val old = mutable.value; val owner = old.owner ?: return
        if (old.loading) return
        val after = if (more) old.nextId else null
        if (more && after == null) return
        generation++; val issued = generation
        cancellation?.cancel(); timeout?.cancel()
        mutable.value = if (more) old.copy(loading = true, error = null, failedMore = false) else ExercisesState(owner = owner, loading = true)
        fun complete(result: ExerciseResult) {
            if (generation != issued || mutable.value.owner != owner || pendingNavigation) return
            generation++; timeout?.cancel(); cancellation = null
            mutable.value = when {
                result.error != null -> if (result.error == ExerciseError.Denied)
                    ExercisesState(owner = owner, error = result.error)
                else mutable.value.copy(loading = false, error = result.error, failedMore = more)
                owner.exerciseId != null && result.detail != null -> ExercisesState(owner = owner, detail = result.detail)
                owner.exerciseId == null -> ExercisesState(owner = owner,
                    items = ((if (more) old.items else emptyList()) + result.items).distinctBy { it.id }, nextId = result.nextId)
                else -> ExercisesState(owner = owner, error = ExerciseError.Malformed)
            }
        }
        timeout = viewModelScope.launch { delay(20_000); cancellation?.cancel(); complete(ExerciseResult(error = ExerciseError.Unavailable)) }
        cancellation = repository.read(owner.scope.uid, after, owner.exerciseId) { result -> viewModelScope.launch { complete(result) } }
    }
    override fun onCleared() { clear(); effects.close() }
}
