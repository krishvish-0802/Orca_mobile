package com.orcamobile.feature.prepare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.model.TextureImageRepository
import com.orcamobile.core.domain.project.ObjectEditor
import com.orcamobile.core.domain.project.TextureTools
import com.orcamobile.core.model.LibraryTexture
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.TextureBake
import com.orcamobile.core.model.TextureLayer
import com.orcamobile.core.model.TextureLayerSettings
import com.orcamobile.core.model.TextureStack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The object the Texture paint works on. */
data class TextureTarget(val project: ProjectId, val objectIndex: Int)

/** What the texture picker is choosing for: a new layer, or another texture for [slot]. */
sealed interface TextureChoice {
    data object NewLayer : TextureChoice

    data class Replace(val slot: Int) : TextureChoice
}

data class TextureUiState(
    val target: TextureTarget? = null,
    val stack: TextureStack = TextureStack(),
    /** The layer painted and edited. */
    val active: Int? = null,
    val library: List<LibraryTexture> = emptyList(),
    /** The texture picker is open. */
    val choosing: TextureChoice? = null,
    val busy: Boolean = false,
    /** Baking: how far, 0..100. */
    val bakingPercent: Int? = null,
    val lastBake: TextureBake? = null,
    val error: String? = null,
) {
    val activeLayer: TextureLayer? get() = active?.let(stack::layer)
}

sealed interface TextureIntent {
    data class Open(val target: TextureTarget) : TextureIntent

    data object Close : TextureIntent

    /** The project changed under the panel (undo, redo): the stack is read again. */
    data object Reload : TextureIntent

    data class Select(val slot: Int) : TextureIntent

    data class Choose(val choice: TextureChoice?) : TextureIntent

    data class PickLibrary(val texture: LibraryTexture) : TextureIntent

    /** An image the user picked (a content URI). */
    data class PickedFile(val uri: String) : TextureIntent

    /** The active layer's settings; written to the engine shortly after the last change. */
    data class Edit(val settings: TextureLayerSettings) : TextureIntent

    data class Remove(val slot: Int) : TextureIntent

    data class Move(val slot: Int, val by: Int) : TextureIntent

    data class SetOptions(val smooth: Boolean, val smoothStrength: Float, val smoothPasses: Int, val displaceBorder: Boolean) : TextureIntent

    /** The active layer over the whole object, or ([erase]) none of it. */
    data class PaintAll(val erase: Boolean) : TextureIntent

    data object Bake : TextureIntent

    data object CancelBake : TextureIntent

    data object DismissError : TextureIntent
}

sealed interface TextureEvent {
    /** Layers or their settings changed (nothing that is drawn). */
    data object Changed : TextureEvent

    /** The engine changed the layers' paint (whole object, a removed layer): the brush reads it again. */
    data object PaintChanged : TextureEvent

    /** The relief is now the object's mesh; the plate holds [objects]. */
    data class Baked(val objects: List<ModelObjectInfo>) : TextureEvent
}

/**
 * The desktop's texture displacement panel (GLGizmoTextureDisplacement) for the
 * Paint tool's Texture kind: the object's layers, their textures and settings,
 * and the bake. The brush paints the active layer's area.
 */
@HiltViewModel
class TextureViewModel @Inject constructor(
    private val tools: TextureTools,
    private val images: TextureImageRepository,
    private val objects: ObjectEditor,
) : ViewModel() {

    private val _state = MutableStateFlow(TextureUiState())
    val state: StateFlow<TextureUiState> = _state.asStateFlow()

    private val _events = Channel<TextureEvent>(Channel.BUFFERED)
    val events: Flow<TextureEvent> = _events.receiveAsFlow()

    private var editJob: Job? = null
    private var bakeJob: Job? = null

    fun onIntent(intent: TextureIntent) {
        when (intent) {
            is TextureIntent.Open -> open(intent.target)
            TextureIntent.Close -> {
                flushEdit()
                bakeJob?.cancel()
                _state.update { TextureUiState(library = it.library) }
            }
            TextureIntent.Reload -> reload()
            is TextureIntent.Select -> {
                flushEdit()
                _state.update { if (it.stack.layer(intent.slot) != null) it.copy(active = intent.slot) else it }
            }
            is TextureIntent.Choose -> _state.update { it.copy(choosing = intent.choice) }
            is TextureIntent.PickLibrary -> useImage(intent.texture.path, intent.texture.name)
            is TextureIntent.PickedFile -> pickFile(intent.uri)
            is TextureIntent.Edit -> edit(intent.settings)
            is TextureIntent.Remove -> change(TextureEvent.PaintChanged) { t -> tools.removeLayer(t.project, t.objectIndex, intent.slot) }
            is TextureIntent.Move -> change(TextureEvent.Changed) { t -> tools.moveLayer(t.project, t.objectIndex, intent.slot, intent.by) }
            is TextureIntent.SetOptions -> change(TextureEvent.Changed) { t ->
                tools.setOptions(t.project, t.objectIndex, intent.smooth, intent.smoothStrength, intent.smoothPasses, intent.displaceBorder)
            }
            is TextureIntent.PaintAll -> {
                val slot = _state.value.active ?: return
                change(TextureEvent.PaintChanged) { t -> tools.paintAll(t.project, t.objectIndex, slot, intent.erase) }
            }
            TextureIntent.Bake -> bake()
            TextureIntent.CancelBake -> bakeJob?.cancel()
            TextureIntent.DismissError -> _state.update { it.copy(error = null) }
        }
    }

    private fun open(target: TextureTarget) {
        if (_state.value.target == target) return
        flushEdit()
        _state.update { TextureUiState(target = target, library = it.library, busy = true) }
        viewModelScope.launch {
            if (_state.value.library.isEmpty()) {
                tools.library().onSuccess { library -> _state.update { it.copy(library = library) } }
            }
            tools.stack(target.project, target.objectIndex)
                .onSuccess { stack -> show(stack) }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
            _state.update { it.copy(busy = false) }
        }
    }

    private fun reload() {
        val target = _state.value.target ?: return
        editJob?.cancel()
        viewModelScope.launch {
            tools.stack(target.project, target.objectIndex).onSuccess { stack -> show(stack) }
        }
    }

    /** The stack in the panel; the active layer kept when it is still there, else the top one. */
    private fun show(stack: TextureStack, active: Int? = _state.value.active) {
        _state.update {
            it.copy(stack = stack, active = active?.takeIf { slot -> stack.layer(slot) != null } ?: stack.layers.lastOrNull()?.slot)
        }
    }

    private fun pickFile(uri: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            images.import(uri)
                .onSuccess { (path, name) -> useImage(path, name) }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    /** The picked image: a new layer, or the active one's texture swapped. */
    private fun useImage(path: String, name: String) {
        val s = _state.value
        val target = s.target ?: return
        val choice = s.choosing ?: TextureChoice.NewLayer
        flushEdit()
        viewModelScope.launch {
            _state.update { it.copy(busy = true, choosing = null) }
            when (choice) {
                TextureChoice.NewLayer -> tools.addLayer(target.project, target.objectIndex, path, name)
                    .onSuccess { (stack, slot) ->
                        show(stack, slot)
                        _events.send(TextureEvent.Changed)
                    }
                is TextureChoice.Replace -> tools.setImage(target.project, target.objectIndex, choice.slot, path, name)
                    .onSuccess { stack ->
                        show(stack)
                        _events.send(TextureEvent.Changed)
                    }
            }.onFailure { e -> _state.update { it.copy(error = e.message) } }
            _state.update { it.copy(busy = false) }
        }
    }

    /** Shown at once; the engine gets it once the slider rests, as one undo step. */
    private fun edit(settings: TextureLayerSettings) {
        val s = _state.value
        val slot = s.active ?: return
        val layer = s.stack.layer(slot) ?: return
        _state.update { it.copy(stack = it.stack.copy(layers = it.stack.layers.map { l -> if (l.slot == slot) l.copy(settings = settings) else l })) }
        if (layer.settings == settings) return
        editJob?.cancel()
        editJob = viewModelScope.launch {
            delay(EDIT_DEBOUNCE_MILLIS)
            writeSettings(slot, settings)
        }
    }

    private suspend fun writeSettings(slot: Int, settings: TextureLayerSettings) {
        val target = _state.value.target ?: return
        tools.setLayer(target.project, target.objectIndex, slot, settings)
            .onSuccess { _events.send(TextureEvent.Changed) }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
    }

    /** A pending settings change written now (before anything else reads the stack). */
    private fun flushEdit() {
        val job = editJob ?: return
        if (!job.isActive) return
        job.cancel()
        val s = _state.value
        val slot = s.active ?: return
        val settings = s.stack.layer(slot)?.settings ?: return
        viewModelScope.launch { writeSettings(slot, settings) }
    }

    private fun change(event: TextureEvent, work: suspend (TextureTarget) -> Result<TextureStack>) {
        val target = _state.value.target ?: return
        flushEdit()
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            work(target)
                .onSuccess { stack ->
                    show(stack)
                    _events.send(event)
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
            _state.update { it.copy(busy = false) }
        }
    }

    private fun bake() {
        val s = _state.value
        val target = s.target ?: return
        if (s.bakingPercent != null) return
        if (!s.stack.anyPainted) {
            _state.update { it.copy(error = NOTHING_PAINTED) }
            return
        }
        editJob?.cancel()
        val pending = s.activeLayer?.settings
        bakeJob = viewModelScope.launch {
            _state.update { it.copy(bakingPercent = 0, lastBake = null, error = null) }
            try {
                // The slider's last value first: the bake uses what the panel shows.
                if (pending != null && s.active != null) tools.setLayer(target.project, target.objectIndex, s.active, pending)
                tools.bake(target.project, target.objectIndex) { percent -> _state.update { it.copy(bakingPercent = percent.coerceIn(0, 100)) } }
                    .onSuccess { result ->
                        _state.update { it.copy(lastBake = result) }
                        tools.stack(target.project, target.objectIndex).onSuccess { stack -> show(stack) }
                        objects.details(target.project, target.objectIndex).onSuccess { update -> _events.send(TextureEvent.Baked(update.objects)) }
                    }
                    .onFailure { e -> _state.update { it.copy(error = e.message) } }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _state.update { it.copy(bakingPercent = null) }
            }
        }
    }

    private companion object {
        const val EDIT_DEBOUNCE_MILLIS = 300L
        const val NOTHING_PAINTED = "Paint where the texture goes first (or use Whole object)."
    }
}
