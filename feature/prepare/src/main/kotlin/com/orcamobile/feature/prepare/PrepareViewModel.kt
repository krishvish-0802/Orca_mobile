package com.orcamobile.feature.prepare

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.preview.GCodePreviewRepository
import com.orcamobile.core.domain.project.ProjectRepository
import com.orcamobile.core.domain.usecase.SendGcodeUseCase
import com.orcamobile.core.domain.usecase.SliceProjectUseCase
import com.orcamobile.core.domain.usecase.TestPrintHostUseCase
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PrepareViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projects: ProjectRepository,
    private val previews: GCodePreviewRepository,
    private val presets: PresetCatalog,
    private val settings: UserSettingsRepository,
    private val sliceProject: SliceProjectUseCase,
    private val testPrintHost: TestPrintHostUseCase,
    private val sendGcode: SendGcodeUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(PrepareUiState())
    val state: StateFlow<PrepareUiState> = _state.asStateFlow()

    private var sliceJob: Job? = null
    private var hostJob: Job? = null
    private var previewJob: Job? = null

    init {
        val modelPath = savedStateHandle.get<String>(PREPARE_MODEL_PATH_ARG)
        viewModelScope.launch { open(modelPath) }
        viewModelScope.launch {
            settings.printHost()?.let { host ->
                updateHost { copy(type = host.type, address = host.baseUrl, apiKey = host.apiKey.orEmpty()) }
            }
            val options = settings.printStartOptions()
            updateHost { copy(startOptions = options) }
        }
    }

    fun onIntent(intent: PrepareIntent) {
        when (intent) {
            PrepareIntent.Slice -> startSlice()
            is PrepareIntent.Select -> _state.update { it.copy(selection = intent.instance) }
            PrepareIntent.PlateEdited -> onPlateEdited()
            PrepareIntent.CancelSlice -> cancelSlice()
            is PrepareIntent.ShowPreview -> _state.update {
                it.copy(showingPreview = intent.show && it.preview is PreviewUiState.Ready)
            }
            is PrepareIntent.SetPreviewLayers -> updatePreview { copy(layers = intent.layers.clampTo(summary.layerCount)) }
            is PrepareIntent.SetPreviewColoring -> updatePreview { copy(coloring = intent.coloring) }
            is PrepareIntent.SetHostType -> updateHost { copy(type = intent.type, status = PrintHostStatus.Idle) }
            is PrepareIntent.SetHostAddress -> updateHost { copy(address = intent.address, status = PrintHostStatus.Idle) }
            is PrepareIntent.SetHostApiKey -> updateHost { copy(apiKey = intent.apiKey, status = PrintHostStatus.Idle) }
            is PrepareIntent.SetStartPrint -> updateHost { copy(startPrint = intent.startPrint) }
            is PrepareIntent.SetTimeLapse -> updateStartOptions { copy(timeLapse = intent.enabled) }
            is PrepareIntent.SetHeatedBedLeveling -> updateStartOptions { copy(heatedBedLeveling = intent.enabled) }
            is PrepareIntent.SetBuildPlate -> updateStartOptions { copy(plate = intent.side) }
            PrepareIntent.TestPrintHost -> testHost()
            PrepareIntent.SendToPrinter -> sendToPrinter()
        }
    }

    override fun onCleared() {
        _state.value.project?.let { projects.release(it.id) }
        releasePreview()
    }

    // --- loading ------------------------------------------------------------

    /** Loads the model and places it on the chosen printer's bed. */
    private suspend fun open(modelPath: String?) {
        if (modelPath == null) {
            _state.update { it.copy(loading = false, error = "No model to open") }
            return
        }
        val selection = settings.presetSelection()
        val bed = selection?.let { presets.bedShape(it.vendor, it.printer).getOrNull() } ?: DEFAULT_BED
        _state.update { it.copy(bed = bed, presets = selection) }

        projects.load(modelPath).fold(
            { project ->
                val (x, y) = bed.center
                projects.centerOn(project.id, x, y)
                _state.update { it.copy(loading = false, project = project) }
            },
            { error -> _state.update { it.copy(loading = false, error = error.message ?: "Could not open the model") } },
        )
    }

    // --- slicing ------------------------------------------------------------

    private fun startSlice() {
        val project = _state.value.project ?: return
        if (sliceJob?.isActive == true) return
        releasePreview()
        _state.update {
            it.copy(
                slice = SliceUiState.Running(0, "Preparing"),
                printHost = it.printHost.copy(status = PrintHostStatus.Idle),
                preview = PreviewUiState.None,
                showingPreview = false,
            )
        }
        sliceJob = viewModelScope.launch {
            sliceProject(project, _state.value.presets).collect { event ->
                val slice = event.toUiState()
                _state.update { it.copy(slice = slice) }
                if (slice is SliceUiState.Done) loadPreview(slice.gcodePath)
            }
        }
    }

    private fun onPlateEdited() {
        if (sliceJob?.isActive == true) return   // the running slice took its snapshot already
        val slice = _state.value.slice
        if (slice is SliceUiState.Idle || slice is SliceUiState.Running) return
        releasePreview()
        _state.update {
            it.copy(
                slice = SliceUiState.Idle,
                preview = PreviewUiState.None,
                showingPreview = false,
                printHost = it.printHost.copy(status = PrintHostStatus.Idle),
            )
        }
    }

    private fun cancelSlice() {
        val job = sliceJob ?: return
        if (!job.isActive) return
        job.cancel()
        _state.update { it.copy(slice = SliceUiState.Canceled) }
    }

    // --- preview ------------------------------------------------------------

    /** Reads the G-code back for the preview and shows it, all layers visible. */
    private fun loadPreview(gcodePath: String) {
        previewJob?.cancel()
        _state.update { it.copy(preview = PreviewUiState.Loading) }
        previewJob = viewModelScope.launch {
            previews.load(gcodePath).fold(
                { summary ->
                    _state.update {
                        it.copy(preview = PreviewUiState.Ready(summary, 0..(summary.layerCount - 1).coerceAtLeast(0)), showingPreview = true)
                    }
                },
                { error -> _state.update { it.copy(preview = PreviewUiState.Failed(error.message ?: "Could not read the G-code")) } },
            )
        }
    }

    private fun releasePreview() {
        previewJob?.cancel()
        (_state.value.preview as? PreviewUiState.Ready)?.let { previews.release(it.summary.id) }
    }

    private fun updatePreview(transform: PreviewUiState.Ready.() -> PreviewUiState.Ready) {
        _state.update { state ->
            val preview = state.preview as? PreviewUiState.Ready ?: return@update state
            state.copy(preview = preview.transform())
        }
    }

    private fun IntRange.clampTo(layerCount: Int): IntRange {
        if (layerCount <= 0) return 0..0
        val last = last.coerceIn(0, layerCount - 1)
        return first.coerceIn(0, last)..last
    }

    // --- sending to a printer -----------------------------------------------

    private fun testHost() {
        val host = _state.value.printHost.config ?: return
        runHostTask(PrintHostStatus.Testing) {
            testPrintHost(host).fold(
                {
                    settings.savePrintHost(host)
                    PrintHostStatus.Connected(it.description)
                },
                { PrintHostStatus.Failed(it.message ?: "Connection failed") },
            )
        }
    }

    private fun sendToPrinter() {
        val host = _state.value.printHost.config ?: return
        val gcode = (_state.value.slice as? SliceUiState.Done)?.gcodePath ?: return
        val startPrint = _state.value.printHost.startPrint
        val options = _state.value.printHost.startOptions
        runHostTask(PrintHostStatus.Sending(0)) {
            sendGcode(host, gcode, startPrint, options) { sent, total ->
                val percent = if (total > 0) (sent * 100 / total).toInt() else 0
                updateHost { if (status is PrintHostStatus.Sending) copy(status = PrintHostStatus.Sending(percent)) else this }
            }.fold(
                {
                    settings.savePrintHost(host)
                    PrintHostStatus.Sent(startPrint)
                },
                { PrintHostStatus.Failed(it.message ?: "Upload failed") },
            )
        }
    }

    private fun runHostTask(initial: PrintHostStatus, task: suspend () -> PrintHostStatus) {
        if (hostJob?.isActive == true) return
        updateHost { copy(status = initial) }
        hostJob = viewModelScope.launch {
            val result = task()
            updateHost { copy(status = result) }
        }
    }

    private fun updateStartOptions(transform: PrintStartOptions.() -> PrintStartOptions) {
        val options = _state.value.printHost.startOptions.transform()
        updateHost { copy(startOptions = options) }
        viewModelScope.launch { settings.savePrintStartOptions(options) }
    }

    private fun updateHost(transform: PrintHostUiState.() -> PrintHostUiState) {
        _state.update { it.copy(printHost = it.printHost.transform()) }
    }

    private fun SliceEvent.toUiState(): SliceUiState = when (this) {
        is SliceEvent.Progress -> SliceUiState.Running(percent, message)
        is SliceEvent.Finished -> when (val r = result) {
            is SliceResult.Succeeded -> SliceUiState.Done(r.statistics, r.gcodePath)
            SliceResult.Canceled -> SliceUiState.Canceled
            is SliceResult.Failed -> SliceUiState.Failed(r.message)
        }
    }

    companion object {
        /** When no printer is chosen: a generic 220 x 220 mm bed, like the engine defaults' slice. */
        val DEFAULT_BED = BedShape(listOf(0.0 to 0.0, 220.0 to 0.0, 220.0 to 220.0, 0.0 to 220.0), 250.0)
    }
}
