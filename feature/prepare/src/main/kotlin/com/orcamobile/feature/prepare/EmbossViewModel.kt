package com.orcamobile.feature.prepare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.model.FontInfo
import com.orcamobile.core.domain.model.FontRepository
import com.orcamobile.core.domain.model.ModelImportRepository
import com.orcamobile.core.domain.project.ObjectEditor
import com.orcamobile.core.domain.project.ObjectTools
import com.orcamobile.core.model.EmbossSource
import com.orcamobile.core.model.EmbossTarget
import com.orcamobile.core.model.EmbossedVolume
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.ObjectEdit
import com.orcamobile.core.model.FeaturePick
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.PlateUpdate
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.VolumeKind
import dagger.hilt.android.lifecycle.HiltViewModel
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

enum class EmbossMode { TEXT, SVG }

/** The desktop's Emboss (text) and SVG tools. Produced only by [EmbossViewModel]. */
data class EmbossUiState(
    val project: ProjectId? = null,
    val active: Boolean = false,
    val mode: EmbossMode = EmbossMode.TEXT,
    val text: String = "Text",
    val fonts: List<FontInfo> = emptyList(),
    val font: FontInfo? = null,
    val letterHeightMm: Double = 10.0,
    val depthMm: Double = 2.0,
    val boldnessMm: Double = 0.0,
    val italic: Boolean = false,
    /** What it becomes on a surface: a raised part, an engraving, or a modifier. */
    val kind: VolumeKind = VolumeKind.PART,
    /** It follows the surface's shape instead of standing flat on it (the desktop's "Use surface"). */
    val useSurface: Boolean = false,
    val svgPath: String? = null,
    val svgName: String? = null,
    val svgWidthMm: Double = 0.0,
    /** Width and height of what would be embossed, mm. */
    val size: Pair<Double, Double>? = null,
    /**
     * The text or SVG on the plate that changes follow; null until one is
     * placed (or after New text), when a tap places a new one.
     */
    val editing: EmbossTarget? = null,
    /** The edited text's font is not on this phone: its name, and the one used instead. */
    val missingFont: Pair<String, String>? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val source: EmbossSource?
        get() = when (mode) {
            EmbossMode.TEXT -> font?.let {
                EmbossSource(
                    text = text,
                    fontPath = it.path,
                    letterHeightMm = letterHeightMm,
                    boldnessMm = boldnessMm,
                    skew = if (italic) ITALIC_SKEW else 0.0,
                    depthMm = depthMm,
                ).takeIf { text.isNotBlank() }
            }
            EmbossMode.SVG -> svgPath?.let { EmbossSource(svgPath = it, svgWidthMm = svgWidthMm, depthMm = depthMm) }
        }

    companion object {
        /** About 11 degrees, as a typical italic. */
        const val ITALIC_SKEW = 0.2
        val KINDS = listOf(VolumeKind.PART, VolumeKind.NEGATIVE_VOLUME, VolumeKind.MODIFIER)
    }
}

sealed interface EmbossIntent {
    data class Open(val project: ProjectId) : EmbossIntent

    data object Close : EmbossIntent

    data class SetMode(val mode: EmbossMode) : EmbossIntent

    data class SetText(val text: String) : EmbossIntent

    data class SetFont(val font: FontInfo) : EmbossIntent

    data class SetLetterHeight(val mm: Double) : EmbossIntent

    data class SetDepth(val mm: Double) : EmbossIntent

    data class SetBoldness(val mm: Double) : EmbossIntent

    data class SetItalic(val italic: Boolean) : EmbossIntent

    data class SetKind(val kind: VolumeKind) : EmbossIntent

    data class SetUseSurface(val on: Boolean) : EmbossIntent

    /** An SVG file the user picked (a content URI). */
    data class PickedSvg(val uri: String, val name: String?) : EmbossIntent

    data class SetSvgWidth(val mm: Double) : EmbossIntent

    /**
     * A tap on a part: a new text there, or, while one is being edited, that
     * text moves there. A tap on a text opens it for editing.
     */
    data class PlaceOn(val at: FeaturePick) : EmbossIntent

    /** As an object of its own, lying on the bed at ([x], [y]). */
    data class AddAsObject(val x: Double, val y: Double) : EmbossIntent

    /** Edits the text or SVG [target]: its settings come into the panel. */
    data class Edit(val target: EmbossTarget) : EmbossIntent

    /** The next tap places another text; the one edited stays as it is. */
    data object NewText : EmbossIntent

    /** Deletes the text being edited. */
    data object Delete : EmbossIntent

    /** The plate dragged or turned the text being edited. */
    data object Moved : EmbossIntent

    /** The Size handle scaled the text being edited by [factor]. */
    data class Resize(val factor: Double) : EmbossIntent
}

sealed interface EmbossEvent {
    /** Something was embossed, changed or deleted; the plate holds [objects] now. */
    data class PlateChanged(val objects: List<ModelObjectInfo>) : EmbossEvent
}

@HiltViewModel
class EmbossViewModel @Inject constructor(
    private val tools: ObjectTools,
    private val objects: ObjectEditor,
    private val fonts: FontRepository,
    private val imports: ModelImportRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EmbossUiState())
    val state: StateFlow<EmbossUiState> = _state.asStateFlow()

    private val _events = Channel<EmbossEvent>(Channel.BUFFERED)
    val events: Flow<EmbossEvent> = _events.receiveAsFlow()

    private var sizeJob: Job? = null
    private var updateJob: Job? = null
    private var fontsLoaded: Job? = null

    fun onIntent(intent: EmbossIntent) {
        when (intent) {
            is EmbossIntent.Open -> open(intent.project)
            EmbossIntent.Close -> {
                updateJob?.cancel()
                _state.update { it.copy(active = false, project = null, busy = false, error = null, editing = null, missingFont = null) }
            }
            is EmbossIntent.SetMode -> change { copy(mode = intent.mode) }
            is EmbossIntent.SetText -> change { copy(text = intent.text) }
            is EmbossIntent.SetFont -> change { copy(font = intent.font, missingFont = null) }
            is EmbossIntent.SetLetterHeight -> change { copy(letterHeightMm = intent.mm.coerceIn(MIN_SIZE_MM, MAX_SIZE_MM)) }
            is EmbossIntent.SetDepth -> change { copy(depthMm = intent.mm.coerceIn(0.05, 100.0)) }
            is EmbossIntent.SetBoldness -> change { copy(boldnessMm = intent.mm.coerceIn(-2.0, 2.0)) }
            is EmbossIntent.SetItalic -> change { copy(italic = intent.italic) }
            is EmbossIntent.SetKind -> change { copy(kind = intent.kind) }
            is EmbossIntent.SetUseSurface -> change { copy(useSurface = intent.on) }
            is EmbossIntent.PickedSvg -> pickSvg(intent)
            is EmbossIntent.SetSvgWidth -> change { copy(svgWidthMm = intent.mm.coerceAtLeast(0.0)) }
            is EmbossIntent.PlaceOn -> placeOn(intent.at)
            is EmbossIntent.AddAsObject -> withSource { project, source ->
                tools.embossObject(project, source, intent.x, intent.y).onSuccess { update ->
                    // A text object is one volume: its own text, edited from now on.
                    _state.update { it.copy(editing = EmbossTarget(InstanceRef(update.placed, 0), 0)) }
                }
            }
            is EmbossIntent.Edit -> edit(intent.target)
            EmbossIntent.NewText -> {
                updateJob?.cancel()
                _state.update { it.copy(editing = null, missingFont = null) }
            }
            EmbossIntent.Delete -> delete()
            EmbossIntent.Moved -> moved()
            is EmbossIntent.Resize -> change {
                when (mode) {
                    EmbossMode.TEXT -> copy(letterHeightMm = (letterHeightMm * intent.factor).coerceIn(MIN_SIZE_MM, MAX_SIZE_MM))
                    EmbossMode.SVG -> {
                        val width = svgWidthMm.takeIf { it > 0 } ?: size?.first ?: return@change this
                        copy(svgWidthMm = (width * intent.factor).coerceIn(MIN_SIZE_MM, MAX_SIZE_MM))
                    }
                }
            }
        }
    }

    private fun open(project: ProjectId) {
        _state.update { it.copy(project = project, active = true, error = null, editing = null) }
        fontsLoaded = viewModelScope.launch {
            val list = runCatching { fonts.fonts() }.getOrDefault(emptyList())
            _state.update { it.copy(fonts = list, font = it.font?.takeIf { f -> f in list } ?: list.firstOrNull()) }
            if (list.isEmpty()) _state.update { it.copy(error = NO_FONTS) }
            measure()
        }
    }

    private fun pickSvg(intent: EmbossIntent.PickedSvg) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            imports.import(intent.uri).onSuccess { path ->
                val name = intent.name ?: path.substringAfterLast('/')
                _state.update { it.copy(svgPath = path, svgName = name, mode = EmbossMode.SVG, svgWidthMm = 0.0, busy = false) }
                measure()
                scheduleUpdate()
            }.onFailure { error -> _state.update { it.copy(busy = false, error = error.message) } }
        }
    }

    /** A setting changed: the size again, and the text being edited follows. */
    private fun change(edit: EmbossUiState.() -> EmbossUiState) {
        _state.update { it.edit().copy(error = null) }
        measure()
        scheduleUpdate()
    }

    /** The shape's size, worked out a moment after the last change. */
    private fun measure() {
        sizeJob?.cancel()
        val source = _state.value.source
        if (source == null) {
            _state.update { it.copy(size = null) }
            return
        }
        sizeJob = viewModelScope.launch {
            delay(SIZE_DELAY_MILLIS)
            tools.embossSize(source).onSuccess { size ->
                _state.update { if (it.source == source) it.copy(size = size) else it }
            }.onFailure { error -> _state.update { if (it.source == source) it.copy(size = null, error = error.message) else it } }
        }
    }

    /** The edited text made anew from the panel, once the changes settle. */
    private fun scheduleUpdate() {
        val target = _state.value.editing ?: return
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            delay(UPDATE_DELAY_MILLIS)
            val s = _state.value
            val project = s.project ?: return@launch
            val source = s.source ?: return@launch
            if (s.editing != target) return@launch
            _state.update { it.copy(busy = true) }
            tools.embossUpdate(project, target.instance.objectIndex, target.volume, s.kind, source, s.useSurface)
                .onSuccess { update ->
                    _state.update { it.copy(busy = false) }
                    _events.send(EmbossEvent.PlateChanged(update.objects))
                }
                .onFailure { error -> _state.update { it.copy(busy = false, error = error.message ?: error.javaClass.simpleName) } }
        }
    }

    private fun placeOn(at: FeaturePick) {
        val s = _state.value
        val project = s.project ?: return
        if (s.busy) return
        val editing = s.editing
        when {
            // The text itself: nothing to do.
            editing != null && editing.instance.objectIndex == at.objectIndex && editing.volume == at.volume -> Unit
            // Another spot of the same object: the text moves there.
            editing != null && editing.instance.objectIndex == at.objectIndex -> runChange {
                tools.embossMove(project, editing.volume, at).onSuccess {
                    _state.update { st -> st.copy(editing = editing.copy(instance = InstanceRef(at.objectIndex, at.instanceIndex))) }
                    if (_state.value.useSurface) scheduleUpdate()
                }
            }
            else -> viewModelScope.launch {
                // A text tapped: edit it; anywhere else: a new one there.
                val tapped = tools.embossed(project, at.objectIndex, at.volume).getOrNull()
                if (tapped != null) {
                    load(EmbossTarget(InstanceRef(at.objectIndex, at.instanceIndex), at.volume), tapped)
                } else {
                    withSource { p, source ->
                        tools.embossOnSurface(p, at, _state.value.kind, source, _state.value.useSurface).onSuccess { update ->
                            _state.update { it.copy(editing = EmbossTarget(InstanceRef(at.objectIndex, at.instanceIndex), update.placed)) }
                        }
                    }
                }
            }
        }
    }

    private fun edit(target: EmbossTarget) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            fontsLoaded?.join()
            tools.embossed(project, target.instance.objectIndex, target.volume).onSuccess { found ->
                if (found == null) {
                    _state.update { it.copy(error = NOT_A_TEXT) }
                } else {
                    load(target, found)
                }
            }.onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    /** A text's settings into the panel, its font matched to one on this phone. */
    private suspend fun load(target: EmbossTarget, found: EmbossedVolume) {
        fontsLoaded?.join()
        updateJob?.cancel()
        val source = found.source
        _state.update { s ->
            if (source.isSvg) {
                s.copy(
                    editing = target,
                    mode = EmbossMode.SVG,
                    svgPath = source.svgPath,
                    svgName = source.svgPath.substringAfterLast('/'),
                    svgWidthMm = source.svgWidthMm,
                    depthMm = source.depthMm,
                    kind = found.kind,
                    useSurface = found.useSurface,
                    missingFont = null,
                    error = null,
                )
            } else {
                val fileName = source.fontPath.substringAfterLast('/').substringAfterLast('\\')
                val font = s.fonts.firstOrNull { it.path == source.fontPath }
                    ?: s.fonts.firstOrNull { it.path.substringAfterLast('/').equals(fileName, ignoreCase = true) }
                val used = font ?: s.font
                s.copy(
                    editing = target,
                    mode = EmbossMode.TEXT,
                    text = source.text,
                    font = used,
                    letterHeightMm = source.letterHeightMm,
                    depthMm = source.depthMm,
                    boldnessMm = source.boldnessMm,
                    italic = source.skew != 0.0,
                    kind = found.kind,
                    useSurface = found.useSurface,
                    missingFont = if (font == null && fileName.isNotEmpty()) fileName to (used?.name ?: "") else null,
                    error = null,
                )
            }
        }
        measure()
    }

    private fun delete() {
        val s = _state.value
        val project = s.project ?: return
        val target = s.editing ?: return
        if (s.busy) return
        updateJob?.cancel()
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            objects.edit(project, target.instance.objectIndex, ObjectEdit.RemoveVolume(target.volume)).onSuccess { update ->
                _state.update { it.copy(busy = false, editing = null) }
                _events.send(EmbossEvent.PlateChanged(update.objects))
            }.onFailure { error -> _state.update { it.copy(busy = false, error = error.message ?: error.javaClass.simpleName) } }
        }
    }

    /** After a drag or twist: the object back on the bed, and a text that follows the surface cut again. */
    private fun moved() {
        val s = _state.value
        val project = s.project ?: return
        val target = s.editing ?: return
        viewModelScope.launch {
            tools.embossSettle(project, target.instance.objectIndex).onSuccess { update ->
                _events.send(EmbossEvent.PlateChanged(update.objects))
            }
            if (_state.value.useSurface) scheduleUpdate()
        }
    }

    /** Runs an engine change that needs the current source, one at a time. */
    private fun withSource(work: suspend (ProjectId, EmbossSource) -> Result<PlateUpdate>) {
        val s = _state.value
        val project = s.project ?: return
        val source = s.source ?: return
        runChange { work(project, source) }
    }

    private fun runChange(work: suspend () -> Result<PlateUpdate>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            work().onSuccess { update ->
                _state.update { it.copy(busy = false) }
                _events.send(EmbossEvent.PlateChanged(update.objects))
            }.onFailure { error -> _state.update { it.copy(busy = false, error = error.message ?: error.javaClass.simpleName) } }
        }
    }

    companion object {
        private const val SIZE_DELAY_MILLIS = 300L
        /** Edits reach the plate this long after the last change, so typing does not re-emboss every letter. */
        private const val UPDATE_DELAY_MILLIS = 350L
        private const val MIN_SIZE_MM = 0.5
        private const val MAX_SIZE_MM = 500.0
        private const val NO_FONTS = "No fonts were found on this phone."
        private const val NOT_A_TEXT = "This part is not a text or an SVG."
    }
}
