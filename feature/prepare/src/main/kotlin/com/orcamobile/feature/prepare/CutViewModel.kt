package com.orcamobile.feature.prepare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.project.MeshEditor
import com.orcamobile.core.model.CutConnector
import com.orcamobile.core.model.CutPlane
import com.orcamobile.core.model.CutPlanePoint
import com.orcamobile.core.model.CutRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.hypot

/**
 * The cut tool: the plane, the connectors on its face and what becomes of the
 * pieces, kept here while the plate shows them; Perform runs the cut in the
 * engine and [events] tells the Prepare screen what it left.
 */
@HiltViewModel
class CutViewModel @Inject constructor(private val meshes: MeshEditor) : ViewModel() {

    private val _state = MutableStateFlow(CutUiState())
    val state: StateFlow<CutUiState> = _state.asStateFlow()

    private val _events = Channel<CutEvent>(Channel.BUFFERED)
    val events: Flow<CutEvent> = _events.receiveAsFlow()

    private var startJob: Job? = null

    fun onIntent(intent: CutIntent) {
        when (intent) {
            is CutIntent.Start -> start(intent)
            CutIntent.Close -> {
                startJob?.cancel()
                _state.value = CutUiState(connector = _state.value.connector)
            }
            is CutIntent.Checked -> _state.update { if (it.active) it.copy(check = intent.check) else it }
            is CutIntent.SetHeight -> editPlane { copy(heightMm = intent.heightMm) }
            is CutIntent.MoveHeight -> editPlane { copy(heightMm = heightMm + intent.deltaMm) }
            is CutIntent.SetTilt -> editPlane { copy(tiltXDegrees = intent.xDegrees, tiltYDegrees = intent.yDegrees) }
            CutIntent.ResetPlane -> {
                val range = _state.value.check?.heightRange
                editPlane { CutPlane(heightMm = range?.let { (it.start + it.endInclusive) / 2 } ?: heightMm) }
            }
            is CutIntent.SetMode -> edit { copy(mode = intent.mode) }
            is CutIntent.SetOptions -> edit { copy(options = intent.options) }
            is CutIntent.SetDovetail -> edit { copy(dovetail = intent.profile) }
            is CutIntent.SetConnector -> {
                val selected = _state.value.selectedConnector
                _state.update { it.copy(connector = intent.connector.at(0.0, 0.0)) }
                if (selected != null) {
                    edit {
                        copy(
                            connectors = connectors.mapIndexed { i, c ->
                                if (i == selected) intent.connector.at(c.u, c.v) else c
                            },
                        )
                    }
                }
            }
            is CutIntent.SetAddingConnectors -> _state.update { it.copy(addingConnectors = intent.adding) }
            is CutIntent.TapPlane -> tap(intent.point)
            is CutIntent.SelectConnector -> _state.update { it.copy(selectedConnector = intent.index) }
            CutIntent.RemoveSelectedConnector -> {
                val selected = _state.value.selectedConnector ?: return
                _state.update { it.copy(selectedConnector = null) }
                edit { copy(connectors = connectors.filterIndexed { i, _ -> i != selected }) }
            }
            CutIntent.RemoveAllConnectors -> {
                _state.update { it.copy(selectedConnector = null) }
                edit { copy(connectors = emptyList()) }
            }
            CutIntent.Perform -> perform()
        }
    }

    private fun start(intent: CutIntent.Start) {
        startJob?.cancel()
        val request = CutRequest(intent.instance)
        _state.update {
            CutUiState(project = intent.project, request = request, connector = it.connector)
        }
        // The plane starts through the middle of the object, as on the desktop.
        startJob = viewModelScope.launch {
            meshes.checkCut(intent.project, request).onSuccess { check ->
                val range = check.heightRange
                _state.update {
                    val current = it.request
                    if (current == null || current.instance != intent.instance) {
                        it
                    } else {
                        it.copy(request = current.copy(plane = CutPlane((range.start + range.endInclusive) / 2)), check = check)
                    }
                }
            }.onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    private fun tap(point: CutPlanePoint) {
        val state = _state.value
        val request = state.request ?: return
        // A tap on a connector picks it.
        val hit = request.connectors.indexOfFirst { hypot(it.u - point.u, it.v - point.v) <= it.sizeMm / 2 }
        when {
            hit >= 0 -> _state.update { it.copy(selectedConnector = hit) }
            state.addingConnectors && point.insideSection -> {
                _state.update { it.copy(selectedConnector = request.connectors.size) }
                edit { copy(connectors = connectors + state.connector.at(point.u, point.v)) }
            }
            else -> _state.update { it.copy(selectedConnector = null) }
        }
    }

    private fun editPlane(change: CutPlane.() -> CutPlane) = edit {
        val plane = plane.change()
        // The plane stays where it crosses the object.
        val range = _state.value.check?.heightRange
        copy(plane = if (range == null) plane else plane.copy(heightMm = plane.heightMm.coerceIn(range)))
    }

    private fun edit(change: CutRequest.() -> CutRequest) {
        _state.update { state ->
            val request = state.request ?: return@update state
            if (state.cutting) state else state.copy(request = request.change(), error = null)
        }
    }

    private fun perform() {
        val state = _state.value
        val project = state.project ?: return
        val request = state.request ?: return
        if (!state.canCut) return
        _state.update { it.copy(cutting = true, error = null) }
        viewModelScope.launch {
            meshes.cut(project, request).onSuccess { update ->
                _state.value = CutUiState(connector = _state.value.connector)
                _events.send(CutEvent.Cut(update.objects, update.placed))
            }.onFailure { error ->
                _state.update { it.copy(cutting = false, error = error.message ?: error.javaClass.simpleName) }
            }
        }
    }
}

