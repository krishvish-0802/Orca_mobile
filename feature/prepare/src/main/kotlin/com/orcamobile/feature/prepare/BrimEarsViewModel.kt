package com.orcamobile.feature.prepare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.domain.project.ObjectEditor
import com.orcamobile.core.domain.project.ObjectTools
import com.orcamobile.core.domain.project.PlateConfigResolver
import com.orcamobile.core.model.BrimEar
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.ObjectEdit
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.SettingTarget
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
import kotlin.math.hypot

/** The desktop's Brim ears for the selected object. Produced only by [BrimEarsViewModel]. */
data class BrimEarsUiState(
    val project: ProjectId? = null,
    val instance: InstanceRef? = null,
    val presets: PresetSelection? = null,
    val ears: List<BrimEar> = emptyList(),
    val selected: Int? = null,
    /** New ears' radius, mm; the selected ear's too. */
    val radiusMm: Double = DEFAULT_RADIUS_MM,
    /** Auto-generate: corners sharper than this, found over this length. */
    val maxAngleDegrees: Double = 125.0,
    val detectionMm: Double = 1.0,
    /** Whether the ears will print: the object's brim type is "painted". Null until known. */
    val brimPainted: Boolean? = null,
    /** Adding ears switched the object's brim type to "painted", so they print. */
    val switchedToPainted: Boolean = false,
    /** The last tap missed the object's outline. */
    val missed: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val active: Boolean get() = instance != null

    companion object {
        const val DEFAULT_RADIUS_MM = 4.0
    }
}

sealed interface BrimEarsIntent {
    data class Open(val project: ProjectId, val instance: InstanceRef, val presets: PresetSelection?) : BrimEarsIntent

    data object Close : BrimEarsIntent

    /** A tap on the plate: on an ear picks it, near the outline adds one. */
    data class Tap(val bedX: Double, val bedY: Double, val outlineX: Double?, val outlineY: Double?) : BrimEarsIntent

    data class SetRadius(val radiusMm: Double) : BrimEarsIntent

    data object RemoveSelected : BrimEarsIntent

    data object RemoveAll : BrimEarsIntent

    data class SetDetection(val maxAngleDegrees: Double, val detectionMm: Double) : BrimEarsIntent

    data object AutoGenerate : BrimEarsIntent

    /** Sets the object's brim type to "painted" so the ears print. */
    data object UsePaintedBrim : BrimEarsIntent
}

sealed interface BrimEarsEvent {
    /** The ears or the brim type changed: an earlier slice is stale. */
    data object Changed : BrimEarsEvent
}

/**
 * Brim ears: kept in the engine on every change, as they are part of the
 * object; the plate shows them from [BrimEarsUiState.ears].
 */
@HiltViewModel
class BrimEarsViewModel @Inject constructor(
    private val tools: ObjectTools,
    private val objects: ObjectEditor,
    private val catalog: ConfigOptionCatalog,
    private val configs: PlateConfigResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(BrimEarsUiState())
    val state: StateFlow<BrimEarsUiState> = _state.asStateFlow()

    private val _events = Channel<BrimEarsEvent>(Channel.BUFFERED)
    val events: Flow<BrimEarsEvent> = _events.receiveAsFlow()

    fun onIntent(intent: BrimEarsIntent) {
        when (intent) {
            is BrimEarsIntent.Open -> open(intent)
            BrimEarsIntent.Close -> _state.update {
                BrimEarsUiState(maxAngleDegrees = it.maxAngleDegrees, detectionMm = it.detectionMm)
            }
            is BrimEarsIntent.Tap -> tap(intent)
            is BrimEarsIntent.SetRadius -> {
                val radius = intent.radiusMm.coerceIn(MIN_RADIUS_MM, MAX_RADIUS_MM)
                val s = _state.value
                _state.update { it.copy(radiusMm = radius) }
                s.selected?.let { i -> save(s.ears.mapIndexed { k, e -> if (k == i) e.copy(radius = radius) else e }) }
            }
            BrimEarsIntent.RemoveSelected -> {
                val s = _state.value
                val i = s.selected ?: return
                _state.update { it.copy(selected = null) }
                save(s.ears.filterIndexed { k, _ -> k != i })
            }
            BrimEarsIntent.RemoveAll -> {
                _state.update { it.copy(selected = null) }
                save(emptyList())
            }
            is BrimEarsIntent.SetDetection -> _state.update {
                it.copy(maxAngleDegrees = intent.maxAngleDegrees.coerceIn(0.0, 180.0), detectionMm = intent.detectionMm.coerceIn(0.0, 100.0))
            }
            BrimEarsIntent.AutoGenerate -> autoGenerate()
            BrimEarsIntent.UsePaintedBrim -> usePaintedBrim()
        }
    }

    private fun open(intent: BrimEarsIntent.Open) {
        _state.update {
            BrimEarsUiState(
                project = intent.project,
                instance = intent.instance,
                presets = intent.presets,
                maxAngleDegrees = it.maxAngleDegrees,
                detectionMm = it.detectionMm,
                busy = true,
            )
        }
        viewModelScope.launch {
            val ears = tools.brimEars(intent.project, intent.instance)
            val radius = defaultRadius(intent.presets)
            val painted = brimType(intent.project, intent.instance.objectIndex, intent.presets)?.let { it == PAINTED }
            _state.update {
                if (it.instance != intent.instance) return@update it
                it.copy(
                    ears = ears.getOrDefault(emptyList()),
                    radiusMm = radius,
                    brimPainted = painted,
                    busy = false,
                    error = ears.exceptionOrNull()?.message,
                )
            }
        }
    }

    private fun tap(tap: BrimEarsIntent.Tap) {
        val s = _state.value
        if (!s.active || s.busy) return
        // On an ear: pick it (the nearest, when they overlap).
        val hit = s.ears.withIndex()
            .filter { (_, e) -> hypot(e.x - tap.bedX, e.y - tap.bedY) <= e.radius }
            .minByOrNull { (_, e) -> hypot(e.x - tap.bedX, e.y - tap.bedY) }
        when {
            hit != null -> _state.update { it.copy(selected = hit.index, radiusMm = hit.value.radius, missed = false) }
            tap.outlineX != null && tap.outlineY != null -> {
                _state.update { it.copy(selected = s.ears.size, missed = false) }
                save(s.ears + BrimEar(tap.outlineX, tap.outlineY, s.radiusMm))
            }
            else -> _state.update { it.copy(selected = null, missed = true) }
        }
    }

    private fun autoGenerate() {
        val s = _state.value
        val project = s.project ?: return
        val instance = s.instance ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            tools.findBrimEars(project, instance, s.maxAngleDegrees, s.detectionMm, s.radiusMm).onSuccess { found ->
                // As the desktop: added to the ears there are, without doubling one up.
                val added = found.filter { f -> s.ears.none { e -> hypot(e.x - f.x, e.y - f.y) < 0.01 } }
                _state.update { it.copy(busy = false) }
                if (added.isNotEmpty()) save(s.ears + added)
            }.onFailure { error -> _state.update { it.copy(busy = false, error = error.message) } }
        }
    }

    private fun save(ears: List<BrimEar>) {
        val s = _state.value
        val project = s.project ?: return
        val instance = s.instance ?: return
        _state.update { it.copy(ears = ears, busy = true, error = null) }
        viewModelScope.launch {
            tools.setBrimEars(project, instance, ears).onSuccess { kept ->
                _state.update { if (it.instance == instance) it.copy(ears = kept, busy = false) else it }
                _events.send(BrimEarsEvent.Changed)
                // Ears only print with the painted brim type: placing one asks for it.
                if (kept.isNotEmpty() && _state.value.brimPainted != true) usePaintedBrim(switched = true)
            }.onFailure { error -> _state.update { it.copy(busy = false, error = error.message) } }
        }
    }

    private fun usePaintedBrim(switched: Boolean = false) {
        val s = _state.value
        val project = s.project ?: return
        val instance = s.instance ?: return
        viewModelScope.launch {
            objects.edit(project, instance.objectIndex, ObjectEdit.SetSetting(SettingTarget.OBJECT, BRIM_TYPE, PAINTED))
                .onSuccess {
                    _state.update { it.copy(brimPainted = true, switchedToPainted = switched) }
                    _events.send(BrimEarsEvent.Changed)
                }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    /** The object's brim type: its own, else the user's change, else the presets'. */
    private suspend fun brimType(project: ProjectId, objectIndex: Int, presets: PresetSelection?): String? {
        objects.details(project, objectIndex).getOrNull()?.details?.settings?.get(BRIM_TYPE)?.let { return it }
        return configs.configFor(presets).overrides[BRIM_TYPE] ?: catalog.values(presets, listOf(BRIM_TYPE)).getOrNull()?.get(BRIM_TYPE)
    }

    /** GLGizmoBrimEars::get_brim_default_radius: eight first-layer line widths. */
    private suspend fun defaultRadius(presets: PresetSelection?): Double {
        val keys = listOf(LINE_WIDTH, NOZZLE)
        val overrides = configs.configFor(presets).overrides
        val values = catalog.values(presets, keys).getOrNull().orEmpty() + overrides.filterKeys { it in keys }
        val nozzle = values[NOZZLE]?.split(',')?.firstOrNull()?.trim()?.toDoubleOrNull() ?: 0.4
        val width = values[LINE_WIDTH]?.trim()?.let { v ->
            if (v.endsWith("%")) v.removeSuffix("%").toDoubleOrNull()?.let { it / 100 * nozzle } else v.toDoubleOrNull()
        }?.takeIf { it > 0 } ?: nozzle
        return (width * 8).coerceIn(MIN_RADIUS_MM, MAX_RADIUS_MM)
    }

    companion object {
        const val MIN_RADIUS_MM = 0.1
        const val MAX_RADIUS_MM = 100.0
        private const val BRIM_TYPE = "brim_type"
        private const val PAINTED = "painted"
        private const val LINE_WIDTH = "initial_layer_line_width"
        private const val NOZZLE = "nozzle_diameter"
    }
}
