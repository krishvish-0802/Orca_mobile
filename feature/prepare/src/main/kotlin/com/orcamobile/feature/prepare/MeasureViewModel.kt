package com.orcamobile.feature.prepare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.project.MeshEditor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Measure and Assembly: the taps on parts, what the engine found under them,
 * and assembly steps run in the engine; [events] tells the Prepare screen when
 * a step moved a part.
 */
@HiltViewModel
class MeasureViewModel @Inject constructor(private val meshes: MeshEditor) : ViewModel() {

    private val _state = MutableStateFlow(MeasureUiState())
    val state: StateFlow<MeasureUiState> = _state.asStateFlow()

    private val _events = Channel<MeasureEvent>(Channel.BUFFERED)
    val events: Flow<MeasureEvent> = _events.receiveAsFlow()

    fun onIntent(intent: MeasureIntent) {
        when (intent) {
            is MeasureIntent.Open -> _state.update { MeasureUiState(project = intent.project, active = true, mode = it.mode) }
            MeasureIntent.Close -> _state.update { MeasureUiState(mode = it.mode) }
            is MeasureIntent.SetMode -> _state.update {
                if (it.mode == intent.mode) it else it.copy(mode = intent.mode, picks = emptyList(), outcome = null, error = null)
            }
            is MeasureIntent.Pick -> _state.update {
                if (!it.active || it.busy) return@update it
                val picks = if (it.picks.size >= 2) listOf(intent.pick) else it.picks + intent.pick
                it.copy(picks = picks, outcome = null, error = null)
            }
            MeasureIntent.Clear -> _state.update { it.copy(picks = emptyList(), outcome = null, error = null) }
            is MeasureIntent.Measured -> _state.update {
                // A tap that found no feature (an assembly mode filters them) does not count.
                val kept = if (intent.outcome.features.size < it.picks.size) it.picks.take(intent.outcome.features.size) else it.picks
                if (it.active) it.copy(picks = kept, outcome = intent.outcome.takeIf { _ -> kept.size == it.picks.size }) else it
            }
            is MeasureIntent.Assemble -> assemble(intent)
        }
    }

    private fun assemble(intent: MeasureIntent.Assemble) {
        val state = _state.value
        val project = state.project ?: return
        val request = state.request ?: return
        if (state.picks.size < 2 || state.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            meshes.assemble(project, state.picks[0], state.picks[1], request, intent.step, intent.value, intent.xyz)
                .onSuccess { update ->
                    _state.update { it.copy(busy = false) }
                    _events.send(MeasureEvent.PlateChanged(update.objects))
                }
                .onFailure { error -> _state.update { it.copy(busy = false, error = error.message ?: error.javaClass.simpleName) } }
        }
    }
}
