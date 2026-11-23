package com.orcamobile.feature.prepare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.model.FontInfo
import com.orcamobile.core.domain.model.FontRepository
import com.orcamobile.core.domain.model.ModelImportRepository
import com.orcamobile.core.domain.project.ObjectTools
import com.orcamobile.core.model.EmbossSource
import com.orcamobile.core.model.FeaturePick
import com.orcamobile.core.model.ModelObjectInfo
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
    val svgPath: String? = null,
    val svgName: String? = null,
    val svgWidthMm: Double = 0.0,
    /** Width and height of what would be embossed, mm. */
    val size: Pair<Double, Double>? = null,
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

    /** An SVG file the user picked (a content URI). */
    data class PickedSvg(val uri: String, val name: String?) : EmbossIntent

    data class SetSvgWidth(val mm: Double) : EmbossIntent

    /** A tap on a part: emboss there. */
    data class PlaceOn(val at: FeaturePick) : EmbossIntent

    /** As an object of its own, lying on the bed at ([x], [y]). */
    data class AddAsObject(val x: Double, val y: Double) : EmbossIntent
}

sealed interface EmbossEvent {
    /** Something was embossed; the plate holds [objects] now. */
    data class PlateChanged(val objects: List<ModelObjectInfo>) : EmbossEvent
}

@HiltViewModel
class EmbossViewModel @Inject constructor(
    private val tools: ObjectTools,
    private val fonts: FontRepository,
    private val imports: ModelImportRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EmbossUiState())
    val state: StateFlow<EmbossUiState> = _state.asStateFlow()

    private val _events = Channel<EmbossEvent>(Channel.BUFFERED)
    val events: Flow<EmbossEvent> = _events.receiveAsFlow()

    private var sizeJob: Job? = null

    fun onIntent(intent: EmbossIntent) {
        when (intent) {
            is EmbossIntent.Open -> open(intent.project)
            EmbossIntent.Close -> _state.update { it.copy(active = false, project = null, busy = false, error = null) }
            is EmbossIntent.SetMode -> change { copy(mode = intent.mode) }
            is EmbossIntent.SetText -> change { copy(text = intent.text) }
            is EmbossIntent.SetFont -> change { copy(font = intent.font) }
            is EmbossIntent.SetLetterHeight -> change { copy(letterHeightMm = intent.mm.coerceIn(0.5, 500.0)) }
            is EmbossIntent.SetDepth -> change { copy(depthMm = intent.mm.coerceIn(0.05, 100.0)) }
            is EmbossIntent.SetBoldness -> change { copy(boldnessMm = intent.mm.coerceIn(-2.0, 2.0)) }
            is EmbossIntent.SetItalic -> change { copy(italic = intent.italic) }
            is EmbossIntent.SetKind -> _state.update { it.copy(kind = intent.kind) }
            is EmbossIntent.PickedSvg -> pickSvg(intent)
            is EmbossIntent.SetSvgWidth -> change { copy(svgWidthMm = intent.mm.coerceAtLeast(0.0)) }
            is EmbossIntent.PlaceOn -> emboss { project, source -> tools.embossOnSurface(project, intent.at, _state.value.kind, source) }
            is EmbossIntent.AddAsObject -> emboss { project, source -> tools.embossObject(project, source, intent.x, intent.y) }
        }
    }

    private fun open(project: ProjectId) {
        _state.update { it.copy(project = project, active = true, error = null) }
        viewModelScope.launch {
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
                _state.update { it.copy(svgPath = path, svgName = name, mode = EmbossMode.SVG, busy = false) }
                measure()
            }.onFailure { error -> _state.update { it.copy(busy = false, error = error.message) } }
        }
    }

    private fun change(edit: EmbossUiState.() -> EmbossUiState) {
        _state.update { it.edit().copy(error = null) }
        measure()
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

    private fun emboss(run: suspend (ProjectId, EmbossSource) -> Result<com.orcamobile.core.model.PlateUpdate>) {
        val s = _state.value
        val project = s.project ?: return
        val source = s.source ?: return
        if (s.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            run(project, source).onSuccess { update ->
                _state.update { it.copy(busy = false) }
                _events.send(EmbossEvent.PlateChanged(update.objects))
            }.onFailure { error -> _state.update { it.copy(busy = false, error = error.message ?: error.javaClass.simpleName) } }
        }
    }

    companion object {
        private const val SIZE_DELAY_MILLIS = 300L
        private const val NO_FONTS = "No fonts were found on this phone."
    }
}
