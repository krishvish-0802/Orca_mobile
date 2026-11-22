package com.orcamobile.feature.prepare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.domain.model.ModelImportRepository
import com.orcamobile.core.domain.project.ObjectEditor
import com.orcamobile.core.domain.project.PlateConfigResolver
import com.orcamobile.core.model.ObjectEdit
import com.orcamobile.core.model.ObjectUpdate
import com.orcamobile.core.model.SettingTarget
import com.orcamobile.core.model.TransformEdit
import com.orcamobile.core.model.VolumeKind
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

/**
 * The desktop's object list for the selected object: its parts, negative
 * volumes, modifiers and support or seam volumes, and its height ranges. Edits
 * run in the engine one at a time; [events] tells the Prepare screen to redraw.
 */
@HiltViewModel
class ObjectListViewModel @Inject constructor(
    private val objects: ObjectEditor,
    private val imports: ModelImportRepository,
    private val catalog: ConfigOptionCatalog,
    private val configs: PlateConfigResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(ObjectListUiState())
    val state: StateFlow<ObjectListUiState> = _state.asStateFlow()

    private val _events = Channel<ObjectListEvent>(Channel.BUFFERED)
    val events: Flow<ObjectListEvent> = _events.receiveAsFlow()

    private var context: ObjectListContext? = null
    /** A volume picked on an object the list is about to show: (object, volume). */
    private var pendingVolume: Pair<Int, Int>? = null
    private var job: Job? = null

    fun onIntent(intent: ObjectListIntent) {
        when (intent) {
            is ObjectListIntent.Show -> show(intent.context)
            is ObjectListIntent.SelectVolume -> {
                val objectIndex = intent.objectIndex
                if (objectIndex != null && objectIndex != context?.objectIndex) {
                    pendingVolume = intent.volume?.let { objectIndex to it }
                } else {
                    pendingVolume = null
                    _state.update { it.copy(selectedVolume = intent.volume) }
                }
            }
            is ObjectListIntent.AddShape -> {
                val size = (context?.bedSizeMm ?: DEFAULT_BED_MM) * SHAPE_FRACTION
                edit(changesShape = true) { ObjectEdit.AddPrimitive(intent.kind, intent.shape, size) }
            }
            is ObjectListIntent.AddFile -> edit(changesShape = true) {
                ObjectEdit.AddFile(intent.kind, imports.import(intent.uri).getOrThrow())
            }
            is ObjectListIntent.SetKind -> edit(changesShape = true) { ObjectEdit.SetKind(intent.volume, intent.kind) }
            is ObjectListIntent.Rename -> edit(changesShape = false) { ObjectEdit.Rename(intent.volume, intent.name) }
            is ObjectListIntent.RemoveVolume -> {
                if (_state.value.selectedVolume == intent.volume) _state.update { it.copy(selectedVolume = null) }
                edit(changesShape = true) { ObjectEdit.RemoveVolume(intent.volume) }
            }
            is ObjectListIntent.EditVolume -> editVolume(intent.edit)
            is ObjectListIntent.SplitToParts -> {
                _state.update { it.copy(selectedVolume = null) }
                edit(changesShape = true) { ObjectEdit.SplitToParts(intent.volume) }
            }
            is ObjectListIntent.Simplify -> edit(changesShape = true) { ObjectEdit.Simplify(intent.volume, intent.settings) }
            is ObjectListIntent.MeshBoolean -> {
                _state.update { it.copy(selectedVolume = null) }
                edit(changesShape = true) { ObjectEdit.MeshBoolean(intent.source, intent.tool, intent.operation, intent.deleteTool) }
            }
            ObjectListIntent.AddRange -> addRange()
            is ObjectListIntent.SetRange -> edit(changesShape = false) {
                ObjectEdit.SetRange(intent.range, intent.minZ, intent.maxZ)
            }
            is ObjectListIntent.SetRangeLayerHeight -> edit(changesShape = false) {
                ObjectEdit.SetSetting(SettingTarget(range = intent.range), "layer_height", intent.layerHeight.toString())
            }
            is ObjectListIntent.RemoveRange -> edit(changesShape = false) { ObjectEdit.RemoveRange(intent.range) }
            ObjectListIntent.DismissError -> _state.update { it.copy(error = null) }
            ObjectListIntent.Refresh -> refresh()
            ObjectListIntent.EnablePaintedFuzzySkin -> edit(changesShape = false) {
                ObjectEdit.SetSetting(SettingTarget.OBJECT, FUZZY_SKIN, "none")
            }
        }
    }

    private fun show(next: ObjectListContext?) {
        val previous = context
        context = next
        if (next == null) {
            job?.cancel()
            _state.value = ObjectListUiState()
            return
        }
        val sameObject = previous?.project == next.project && previous.objectIndex == next.objectIndex
        if (sameObject && _state.value.details != null && previous?.revision == next.revision) return
        if (!sameObject) {
            val picked = pendingVolume?.takeIf { it.first == next.objectIndex }?.second
            pendingVolume = null
            _state.value = ObjectListUiState(project = next.project, objectIndex = next.objectIndex, selectedVolume = picked)
        }
        reload()
    }

    /** Reads the object again, e.g. after the plate changed it. */
    private fun reload() {
        val current = context ?: return
        job?.cancel()
        job = viewModelScope.launch {
            objects.details(current.project, current.objectIndex)
                .onSuccess { update -> apply(update) }
                .onFailure { error -> _state.update { it.copy(details = null, error = error.message) } }
        }
    }

    private fun refresh() {
        val current = context ?: return
        val before = _state.value.details ?: return
        if (_state.value.busy) return
        job?.cancel()
        job = viewModelScope.launch {
            objects.details(current.project, current.objectIndex).onSuccess { update ->
                apply(update)
                if (update.details.allSettings() != before.allSettings()) _events.send(ObjectListEvent.SettingsChanged)
            }
        }
    }

    private fun edit(changesShape: Boolean, make: suspend () -> ObjectEdit) {
        val current = context ?: return
        if (!current.editable || _state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            val result = runCatching { make() }.mapCatching { edit ->
                objects.edit(current.project, current.objectIndex, edit).getOrThrow()
            }
            result.onSuccess { update ->
                apply(update)
                _events.send(if (changesShape) ObjectListEvent.PlateChanged(update.objects) else ObjectListEvent.SettingsChanged)
            }.onFailure { error ->
                _state.update { it.copy(busy = false, error = error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    private suspend fun apply(update: ObjectUpdate) {
        val fuzzyDisabled = effectiveFuzzySkin(update) == FUZZY_SKIN_DISABLED
        _state.update {
            val volumes = update.details.volumes.size
            it.copy(
                details = update.details,
                busy = false,
                selectedVolume = it.selectedVolume?.takeIf { v -> v < volumes },
                fuzzySkinDisabled = fuzzyDisabled,
            )
        }
    }

    /** The object's fuzzy skin setting: its own, else the user's change, else the presets'. */
    private suspend fun effectiveFuzzySkin(update: ObjectUpdate): String? {
        update.details.settings[FUZZY_SKIN]?.let { return it }
        val presets = context?.presets
        return configs.configFor(presets).overrides[FUZZY_SKIN]
            ?: catalog.values(presets, listOf(FUZZY_SKIN)).getOrNull()?.get(FUZZY_SKIN)
    }

    /** The placement panel's edits, made relative to the selected volume's current placement. */
    private fun editVolume(edit: TransformEdit) {
        val index = _state.value.selectedVolume ?: return
        val transform = _state.value.volume?.transform ?: return
        val (position, rotation, scale) = when (edit) {
            is TransformEdit.Position -> Triple(
                transform.position.copy(x = edit.x, y = edit.y, z = edit.z ?: transform.position.z),
                transform.rotationDegrees,
                transform.scale,
            )
            is TransformEdit.Rotation -> Triple(transform.position, edit.degrees, transform.scale)
            is TransformEdit.Scale -> Triple(transform.position, transform.rotationDegrees, edit.factors)
            TransformEdit.Reset -> Triple(transform.position, transform.rotationDegrees.map { 0.0 }, transform.scale.map { 1.0 })
            is TransformEdit.Mirror -> return   // volumes are not mirrored here
        }
        edit(changesShape = true) { ObjectEdit.VolumeTransform(index, position, rotation, scale) }
    }

    private fun addRange() {
        val current = context ?: return
        val ranges = _state.value.details?.layerRanges.orEmpty()
        val start = ranges.maxOfOrNull { it.maxZ } ?: 0.0
        edit(changesShape = false) {
            val config = configs.configFor(current.presets)
            val layerHeight = config.overrides["layer_height"]
                ?: catalog.values(current.presets, listOf("layer_height")).getOrNull()?.get("layer_height")
                ?: DEFAULT_LAYER_HEIGHT
            ObjectEdit.AddRange(start, start + NEW_RANGE_HEIGHT_MM, layerHeight)
        }
    }

    companion object {
        private const val DEFAULT_BED_MM = 220.0
        private const val SHAPE_FRACTION = 0.1
        private const val DEFAULT_LAYER_HEIGHT = "0.2"
        private const val FUZZY_SKIN = "fuzzy_skin"
        private const val FUZZY_SKIN_DISABLED = "disabled_fuzzy"

        /** The kinds a volume may be changed to: what it is, a part, or anything but a part. */
        fun kindsFor(kind: VolumeKind): List<VolumeKind> = VolumeKind.addable.let { if (kind in it) it else it + kind }
    }
}
