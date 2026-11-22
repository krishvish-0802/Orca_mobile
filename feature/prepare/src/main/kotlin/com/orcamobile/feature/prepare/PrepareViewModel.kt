package com.orcamobile.feature.prepare

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.presets.PresetPicker
import com.orcamobile.core.domain.preview.GCodePreviewRepository
import com.orcamobile.core.domain.project.ProjectRepository
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.domain.usecase.PlateToolsUseCase
import com.orcamobile.core.domain.usecase.SendGcodeUseCase
import com.orcamobile.core.domain.usecase.SliceProjectUseCase
import com.orcamobile.core.domain.usecase.TestPrintHostUseCase
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.PlateUpdate
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.TransformEdit
import com.orcamobile.core.model.PresetChoices
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PrepareViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projects: ProjectRepository,
    private val printSettings: PrintSettingsRepository,
    private val previews: GCodePreviewRepository,
    private val presets: PresetCatalog,
    private val picker: PresetPicker,
    private val settings: UserSettingsRepository,
    private val sliceProject: SliceProjectUseCase,
    private val plateTools: PlateToolsUseCase,
    private val testPrintHost: TestPrintHostUseCase,
    private val sendGcode: SendGcodeUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(PrepareUiState())
    val state: StateFlow<PrepareUiState> = _state.asStateFlow()

    private var sliceJob: Job? = null
    private var hostJob: Job? = null
    private var previewJob: Job? = null
    private var transformJob: Job? = null

    init {
        val modelPath = savedStateHandle.get<String>(PREPARE_MODEL_PATH_ARG)
        viewModelScope.launch {
            picker.load()
            applyPresets(picker.choices.value)
            open(modelPath)
            picker.choices.collect { applyPresets(it) }
        }
        viewModelScope.launch {
            settings.printHost()?.let { host ->
                updateHost { copy(type = host.type, address = host.baseUrl, apiKey = host.apiKey.orEmpty()) }
            }
            val options = settings.printStartOptions()
            updateHost { copy(startOptions = options) }
        }
        viewModelScope.launch { followSettingsChanges() }
    }

    fun onIntent(intent: PrepareIntent) {
        when (intent) {
            PrepareIntent.Slice -> startSlice()
            is PrepareIntent.SelectVendor -> viewModelScope.launch { picker.selectVendor(intent.vendor) }
            is PrepareIntent.SelectPrinter -> viewModelScope.launch { picker.selectPrinter(intent.printer) }
            is PrepareIntent.SelectProcess -> viewModelScope.launch { picker.selectProcess(intent.process) }
            is PrepareIntent.SelectFilament -> viewModelScope.launch { picker.selectFilament(intent.filament) }
            is PrepareIntent.Select -> {
                _state.update { it.copy(selection = intent.instance) }
                loadTransform()
            }
            PrepareIntent.PlateEdited -> {
                dropStaleResult()
                loadTransform()
            }
            is PrepareIntent.EditTransform -> editTransform(intent.edit)
            is PrepareIntent.SetUniformScale -> _state.update { it.copy(uniformScale = intent.uniform) }
            is PrepareIntent.SetLayOnFace -> _state.update { it.copy(layingOnFace = intent.on && it.plateEditable) }
            is PrepareIntent.LaidOnFace -> {
                _state.update { it.copy(selection = intent.instance, layingOnFace = false) }
                dropStaleResult()
                loadTransform()
            }
            PrepareIntent.Arrange -> runPlateTool(PlateTask.ARRANGE) { project, presets ->
                plateTools.arrange(project, presets, _state.value.arrangeOptions)
            }
            is PrepareIntent.SetArrangeOptions -> _state.update { it.copy(arrangeOptions = intent.options) }
            PrepareIntent.Orient -> {
                val only = _state.value.selection
                runPlateTool(PlateTask.ORIENT) { project, presets -> plateTools.orient(project, presets, only) }
            }
            is PrepareIntent.AddCopies -> _state.value.selection?.let { source ->
                val task = if (intent.count == null) PlateTask.FILL_BED else PlateTask.COPY
                runPlateTool(task) { project, presets ->
                    plateTools.addCopies(project, presets, source, intent.count, _state.value.arrangeOptions)
                }
            }
            PrepareIntent.DeleteSelected -> _state.value.selection?.let { selected ->
                runPlateTool(PlateTask.DELETE, keepSelection = false) { project, _ -> plateTools.remove(project, selected) }
            }
            PrepareIntent.DismissPlateNotice -> _state.update { it.copy(plateNotice = null) }
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
        val bed = _state.value.bed ?: DEFAULT_BED
        projects.load(modelPath).fold(
            { project ->
                val (x, y) = bed.center
                projects.centerOn(project.id, x, y)
                _state.update { it.copy(loading = false, project = project) }
            },
            { error -> _state.update { it.copy(loading = false, error = error.message ?: "Could not open the model") } },
        )
    }

    // --- presets ------------------------------------------------------------

    /**
     * Follows the app-wide preset choice: a new printer brings its bed, and the
     * model is placed in its middle; any change makes an earlier slice stale.
     * While a choice is incomplete (a vendor picked, no printer yet) the plate
     * keeps the previous presets.
     */
    private suspend fun applyPresets(choices: PresetChoices) {
        _state.update { it.copy(choices = choices) }
        val selection = choices.selection
        val old = _state.value
        if ((selection == old.presets && old.bed != null) || (selection == null && old.presets != null)) return
        val printerChanged = selection?.vendor != old.presets?.vendor || selection?.printer != old.presets?.printer
        val bed = if (printerChanged || old.bed == null) {
            selection?.let { presets.bedShape(it.vendor, it.printer).getOrNull() } ?: DEFAULT_BED
        } else {
            old.bed
        }
        _state.update { it.copy(presets = selection, bed = bed) }
        val project = _state.value.project
        if (project != null && printerChanged) {
            val (x, y) = bed.center
            projects.centerOn(project.id, x, y)
            _state.update { it.copy(plateRevision = it.plateRevision + 1) }
        }
        if (project != null) dropStaleResult()
    }

    // --- slicing ------------------------------------------------------------

    /**
     * Runs a plate tool in the engine, one at a time, then shows the plate it
     * left: objects redrawn, an earlier slice dropped, and a notice when
     * something did not fit.
     */
    private fun runPlateTool(
        task: PlateTask,
        keepSelection: Boolean = true,
        tool: suspend (ProjectId, PresetSelection?) -> Result<PlateUpdate>,
    ) {
        val start = _state.value
        val project = start.project ?: return
        if (!start.plateEditable) return
        _state.update { it.copy(plateTask = task, plateNotice = null) }
        viewModelScope.launch {
            val result = tool(project.id, start.presets)
            result.onSuccess { update ->
                _state.update {
                    it.copy(
                        plateTask = null,
                        project = it.project?.copy(objects = update.objects),
                        selection = it.selection.takeIf { keepSelection }?.takeIf { ref -> update.objects.holds(ref) },
                        plateRevision = it.plateRevision + 1,
                        plateNotice = notice(task, update),
                    )
                }
                dropStaleResult()
                loadTransform()
            }.onFailure { error ->
                _state.update {
                    it.copy(plateTask = null, plateNotice = PlateNotice.Failed(error.message ?: error.javaClass.simpleName))
                }
            }
        }
    }

    /** Reads the selected instance's transform for the object panel (none without a selection). */
    private fun loadTransform() {
        transformJob?.cancel()
        val project = _state.value.project
        val selection = _state.value.selection
        if (project == null || selection == null) {
            _state.update { it.copy(transform = null) }
            return
        }
        transformJob = viewModelScope.launch {
            val transform = plateTools.transform(project.id, selection).getOrNull()?.transform
            _state.update { if (it.selection == selection) it.copy(transform = transform) else it }
        }
    }

    private fun editTransform(edit: TransformEdit) {
        val start = _state.value
        val project = start.project ?: return
        val selection = start.selection ?: return
        if (!start.plateEditable) return
        transformJob?.cancel()
        transformJob = viewModelScope.launch {
            plateTools.transform(project.id, selection, edit).onSuccess { update ->
                _state.update {
                    it.copy(
                        transform = update.transform.takeIf { _ -> it.selection == selection },
                        project = it.project?.copy(objects = update.objects),
                        plateRevision = it.plateRevision + 1,
                    )
                }
                dropStaleResult()
            }.onFailure { error ->
                _state.update { it.copy(plateNotice = PlateNotice.Failed(error.message ?: error.javaClass.simpleName)) }
                loadTransform()
            }
        }
    }

    private fun notice(task: PlateTask, update: PlateUpdate): PlateNotice? = when (task) {
        PlateTask.ARRANGE -> update.notPlaced.takeIf { it > 0 }?.let(PlateNotice::NotPlaced)
        PlateTask.COPY -> PlateNotice.CopiesAdded(update.placed, update.notPlaced)
        PlateTask.FILL_BED -> if (update.placed > 0) PlateNotice.CopiesAdded(update.placed, 0) else PlateNotice.BedFull
        PlateTask.ORIENT, PlateTask.DELETE -> null
    }

    private fun List<ModelObjectInfo>.holds(ref: InstanceRef): Boolean =
        ref.instanceIndex < (getOrNull(ref.objectIndex)?.instances ?: 0)

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

    /** Counts the settings changes; a change after slicing makes the result stale, as moving an object does. */
    private suspend fun followSettingsChanges() {
        var previous: Map<String, String>? = null
        val presets = _state.map { it.presets }.distinctUntilChanged()
        combine(printSettings.edits, presets) { edits, selection ->
            if (edits.appliesTo(selection)) edits.values else emptyMap()
        }.distinctUntilChanged().collect { changes ->
            _state.update { it.copy(changedSettings = changes.size) }
            if (previous != null) dropStaleResult()
            previous = changes
        }
    }

    private fun dropStaleResult() {
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
