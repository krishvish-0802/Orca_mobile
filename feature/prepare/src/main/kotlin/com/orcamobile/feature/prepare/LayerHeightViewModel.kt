package com.orcamobile.feature.prepare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.project.ObjectTools
import com.orcamobile.core.domain.project.PlateConfigResolver
import com.orcamobile.core.model.LayerBrush
import com.orcamobile.core.model.LayerProfile
import com.orcamobile.core.model.LayerProfileAction
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.ProjectId
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
import kotlin.math.abs

/** The desktop's Variable layer height for the selected object. Produced only by [LayerHeightViewModel]. */
data class LayerHeightUiState(
    val project: ProjectId? = null,
    val objectIndex: Int? = null,
    val presets: PresetSelection? = null,
    val profile: LayerProfile? = null,
    val quality: Double = 0.5,
    val smoothRadius: Int = 5,
    val keepMin: Boolean = false,
    /** How tall a band one brush stroke changes, mm (the desktop's default). */
    val bandMm: Double = 2.0,
    /** The brush smooths instead of making layers thicker or thinner. */
    val smoothBrush: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val active: Boolean get() = objectIndex != null
}

sealed interface LayerHeightIntent {
    data class Open(val project: ProjectId, val objectIndex: Int, val presets: PresetSelection?) : LayerHeightIntent

    data object Close : LayerHeightIntent

    data class SetQuality(val quality: Double) : LayerHeightIntent

    data object Adaptive : LayerHeightIntent

    data class SetSmoothing(val radius: Int, val keepMin: Boolean) : LayerHeightIntent

    data object Smooth : LayerHeightIntent

    data class SetBand(val bandMm: Double) : LayerHeightIntent

    data class SetSmoothBrush(val smooth: Boolean) : LayerHeightIntent

    /** A brush step at [zMm]: thicker by [deltaMm] when positive, thinner when negative (or smoother). */
    data class Brush(val zMm: Double, val deltaMm: Double) : LayerHeightIntent

    data object Reset : LayerHeightIntent
}

sealed interface LayerHeightEvent {
    /** The object's layers changed: an earlier slice is stale. */
    data object Changed : LayerHeightEvent
}

@HiltViewModel
class LayerHeightViewModel @Inject constructor(
    private val tools: ObjectTools,
    private val configs: PlateConfigResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(LayerHeightUiState())
    val state: StateFlow<LayerHeightUiState> = _state.asStateFlow()

    private val _events = Channel<LayerHeightEvent>(Channel.BUFFERED)
    val events: Flow<LayerHeightEvent> = _events.receiveAsFlow()

    /** Brush steps that came in while one ran: (z, summed delta). */
    private var pendingBrush: Pair<Double, Double>? = null

    fun onIntent(intent: LayerHeightIntent) {
        when (intent) {
            is LayerHeightIntent.Open -> {
                _state.update {
                    it.copy(project = intent.project, objectIndex = intent.objectIndex, presets = intent.presets, profile = null, error = null)
                }
                run(LayerProfileAction.Get, changes = false)
            }
            LayerHeightIntent.Close -> _state.update {
                LayerHeightUiState(quality = it.quality, smoothRadius = it.smoothRadius, keepMin = it.keepMin, bandMm = it.bandMm)
            }
            is LayerHeightIntent.SetQuality -> _state.update { it.copy(quality = intent.quality.coerceIn(0.0, 1.0)) }
            LayerHeightIntent.Adaptive -> run(LayerProfileAction.Adaptive(_state.value.quality))
            is LayerHeightIntent.SetSmoothing -> _state.update { it.copy(smoothRadius = intent.radius.coerceIn(1, 10), keepMin = intent.keepMin) }
            LayerHeightIntent.Smooth -> run(LayerProfileAction.Smooth(_state.value.smoothRadius, _state.value.keepMin))
            is LayerHeightIntent.SetBand -> _state.update { it.copy(bandMm = intent.bandMm.coerceIn(0.5, 20.0)) }
            is LayerHeightIntent.SetSmoothBrush -> _state.update { it.copy(smoothBrush = intent.smooth) }
            is LayerHeightIntent.Brush -> brush(intent.zMm, intent.deltaMm)
            LayerHeightIntent.Reset -> run(LayerProfileAction.Reset)
        }
    }

    private fun brush(z: Double, delta: Double) {
        if (delta == 0.0) return
        if (_state.value.busy) {
            val pending = pendingBrush
            pendingBrush = if (pending == null) z to delta else z to pending.second + delta
            return
        }
        val s = _state.value
        val brush = when {
            s.smoothBrush -> LayerBrush.SMOOTH
            delta > 0 -> LayerBrush.INCREASE
            else -> LayerBrush.DECREASE
        }
        run(LayerProfileAction.Brush(z, abs(delta), s.bandMm, brush), next = {
            pendingBrush?.let { (pz, pd) ->
                pendingBrush = null
                brush(pz, pd)
            }
        })
    }

    private fun run(action: LayerProfileAction, changes: Boolean = true, next: () -> Unit = {}) {
        val s = _state.value
        val project = s.project ?: return
        val objectIndex = s.objectIndex ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val config = configs.configFor(s.presets)
            tools.layerProfile(project, objectIndex, config, action).onSuccess { profile ->
                _state.update { if (it.objectIndex == objectIndex) it.copy(profile = profile, busy = false) else it.copy(busy = false) }
                if (changes) _events.send(LayerHeightEvent.Changed)
                next()
            }.onFailure { error ->
                pendingBrush = null
                _state.update { it.copy(busy = false, error = error.message ?: error.javaClass.simpleName) }
            }
        }
    }
}
