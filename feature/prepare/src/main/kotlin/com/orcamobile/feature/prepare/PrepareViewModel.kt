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
import com.orcamobile.core.model.PaintBrush
import com.orcamobile.core.model.PlateAction
import com.orcamobile.core.model.PlateSettingKeys
import com.orcamobile.core.model.Plates
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

    /** Each plate's last slice, while it still matches the plate; the current plate's is [PrepareUiState.slice]. */
    private val plateResults = mutableMapOf<Int, SliceUiState.Done>()

    /** Grows when something the prime tower depends on changed besides what the state shows (paint, settings). */
    private val towerInputs = MutableStateFlow(0)
    private var towerJob: Job? = null

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
        viewModelScope.launch { followWipeTower() }
    }

    fun onIntent(intent: PrepareIntent) {
        when (intent) {
            PrepareIntent.Slice -> startSlice()
            PrepareIntent.SliceAll -> sliceAllPlates()
            is PrepareIntent.MoveWipeTower -> {
                val plate = _state.value.currentPlate
                changePlates(stale = setOf(plate)) { project ->
                    plateTools.plates(project, PlateAction.SetSetting(plate, PlateSettingKeys.WIPE_TOWER_X, intent.x.mm())).getOrThrow()
                    plateTools.plates(project, PlateAction.SetSetting(plate, PlateSettingKeys.WIPE_TOWER_Y, intent.y.mm())).getOrThrow()
                }
            }
            is PrepareIntent.SelectPlate -> selectPlate(intent.plate)
            PrepareIntent.AddPlate -> changePlates { project ->
                val added = plateTools.plates(project, PlateAction.Add).getOrThrow()
                plateTools.plates(project, PlateAction.Select(added.plates.lastIndex)).getOrThrow()
            }
            is PrepareIntent.RemovePlate -> removePlate(intent.plate)
            is PrepareIntent.RenamePlate -> changePlates { plateTools.plates(it, PlateAction.Rename(intent.plate, intent.name.trim())).getOrThrow() }
            is PrepareIntent.SetPlateSetting -> {
                val action = intent.value?.let { PlateAction.SetSetting(intent.plate, intent.key, it) }
                    ?: PlateAction.ResetSetting(intent.plate, intent.key)
                changePlates(stale = setOf(intent.plate)) { plateTools.plates(it, action).getOrThrow() }
            }
            is PrepareIntent.MoveToPlate -> moveSelectionToPlate(intent.plate)
            PrepareIntent.ArrangeAllPlates -> runPlateTool(PlateTask.ARRANGE_ALL, keepSelection = false) { project, presets ->
                plateTools.arrangeAllPlates(project, presets, _state.value.arrangeOptions)
            }
            is PrepareIntent.SelectVendor -> viewModelScope.launch { picker.selectVendor(intent.vendor) }
            is PrepareIntent.SelectPrinter -> viewModelScope.launch { picker.selectPrinter(intent.printer) }
            is PrepareIntent.SelectProcess -> viewModelScope.launch { picker.selectProcess(intent.process) }
            is PrepareIntent.SelectFilament -> viewModelScope.launch { picker.selectFilament(intent.slot, intent.filament) }
            PrepareIntent.AddFilament -> viewModelScope.launch { picker.addFilament() }
            is PrepareIntent.RemoveFilament -> removeFilament(intent.slot)
            is PrepareIntent.SetFilamentColour -> viewModelScope.launch { picker.setFilamentColour(intent.slot, intent.colour) }
            is PrepareIntent.Select -> {
                // An object of another plate (picked from the list): show its plate first.
                val plate = intent.instance?.let { ref -> _state.value.project?.objects?.getOrNull(ref.objectIndex)?.plateOf(ref.instanceIndex) }
                if (plate != null && plate != _state.value.currentPlate) {
                    selectPlate(plate, thenSelect = intent.instance)
                    return
                }
                // Painting belongs to one object: choosing another (or none) ends it.
                _state.update {
                    it.copy(
                        selection = intent.instance,
                        group = emptyList(),
                        paint = it.paint.takeIf { _ -> intent.instance?.objectIndex == it.selection?.objectIndex },
                    )
                }
                loadTransform()
            }
            is PrepareIntent.ToggleSelected -> toggleSelected(intent.instance)
            is PrepareIntent.Align -> {
                val refs = _state.value.selected
                if (refs.isNotEmpty()) {
                    runPlateTool(PlateTask.ALIGN) { project, presets ->
                        plateTools.align(project, presets, refs, intent.mode, intent.toPlate)
                    }
                }
            }
            PrepareIntent.PlateEdited -> {
                dropStaleResult()
                loadTransform()
            }
            is PrepareIntent.EditTransform -> editTransform(intent.edit)
            is PrepareIntent.SetUniformScale -> _state.update { it.copy(uniformScale = intent.uniform) }
            is PrepareIntent.SetHandleMode -> _state.update { it.copy(handleMode = intent.mode) }
            is PrepareIntent.StartPainting -> _state.update {
                if (!it.plateEditable || it.selection == null) {
                    it
                } else {
                    it.copy(paint = PaintBrush(kind = intent.kind, modifier = intent.modifier), layingOnFace = false, group = emptyList())
                }
            }
            is PrepareIntent.PaintedModifierMade -> _state.update {
                val brush = it.paint
                if (brush?.kind == com.orcamobile.core.model.PaintKind.PAINTED_MODIFIER) it.copy(paint = brush.copy(modifier = intent.volume)) else it
            }
            is PrepareIntent.SetBrush -> _state.update { if (it.paint == null) it else it.copy(paint = intent.brush) }
            PrepareIntent.ClearPaint -> _state.update { if (it.paint == null) it else it.copy(paintClearRequest = it.paintClearRequest + 1) }
            PrepareIntent.StopPainting -> _state.update { it.copy(paint = null) }
            PrepareIntent.Painted -> {
                dropStaleResult()
                towerInputs.update { it + 1 }
            }
            is PrepareIntent.SetLayOnFace -> _state.update { it.copy(layingOnFace = intent.on && it.plateEditable) }
            is PrepareIntent.ObjectChanged -> {
                _state.update { it.copy(project = it.project?.copy(objects = intent.objects), plateRevision = it.plateRevision + 1) }
                dropStaleResult()
                loadTransform()
            }
            PrepareIntent.ObjectSettingsChanged -> {
                dropStaleResult()
                towerInputs.update { it + 1 }
            }
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
                val selected = _state.value.selected
                runPlateTool(PlateTask.ORIENT) { project, presets ->
                    if (selected.isEmpty()) {
                        plateTools.orient(project, presets, null)
                    } else {
                        // Each selected object in turn; the last result shows the plate.
                        selected.fold(Result.success(PlateUpdate(emptyList()))) { last, ref ->
                            last.fold({ plateTools.orient(project, presets, ref) }, { Result.failure(it) })
                        }
                    }
                }
            }
            is PrepareIntent.AddCopies -> _state.value.selection?.let { source ->
                val task = if (intent.count == null) PlateTask.FILL_BED else PlateTask.COPY
                runPlateTool(task) { project, presets ->
                    plateTools.addCopies(project, presets, source, intent.count, _state.value.arrangeOptions)
                }
            }
            PrepareIntent.DeleteSelected -> _state.value.selected.takeIf { it.isNotEmpty() }?.let { selected ->
                // Last first, so the numbers of the ones still to go do not shift.
                val order = selected.sortedWith(compareByDescending<InstanceRef> { it.objectIndex }.thenByDescending { it.instanceIndex })
                runPlateTool(PlateTask.DELETE, keepSelection = false) { project, _ ->
                    order.fold(Result.success(PlateUpdate(emptyList()))) { last, ref ->
                        last.fold({ plateTools.remove(project, ref) }, { Result.failure(it) })
                    }
                }
            }
            PrepareIntent.DismissPlateNotice -> _state.update { it.copy(plateNotice = null) }
            PrepareIntent.SplitToObjects -> _state.value.selection?.let { selected ->
                runPlateTool(PlateTask.SPLIT, keepSelection = false) { project, _ ->
                    plateTools.splitToObjects(project, selected.objectIndex)
                }
            }
            is PrepareIntent.OpenMeshTool -> _state.update {
                if ((it.selection != null || intent.tool == MeshTool.MEASURE || intent.tool == MeshTool.EMBOSS) && it.plateEditable) {
                    it.copy(meshTool = intent.tool, layingOnFace = false, simplifyCounts = null, group = emptyList())
                } else {
                    it
                }
            }
            PrepareIntent.CloseMeshTool -> _state.update { it.copy(meshTool = null, simplifyCounts = null, textToEdit = null) }
            is PrepareIntent.EditText -> _state.update {
                val selected = it.selection
                if (selected == null || !it.plateEditable) {
                    it
                } else {
                    it.copy(
                        meshTool = MeshTool.EMBOSS,
                        layingOnFace = false,
                        textToEdit = com.orcamobile.core.model.EmbossTarget(selected, intent.volume),
                    )
                }
            }
            is PrepareIntent.SetSimplify -> _state.update { it.copy(simplify = intent.settings) }
            is PrepareIntent.SimplifyCounted -> _state.update {
                if (it.meshTool == MeshTool.SIMPLIFY) it.copy(simplifyCounts = intent.counts) else it
            }
            is PrepareIntent.Cut -> {
                _state.update {
                    it.copy(
                        meshTool = null,
                        project = it.project?.copy(objects = intent.objects),
                        selection = null,
                        plateRevision = it.plateRevision + 1,
                        plateNotice = PlateNotice.Cut(intent.pieces),
                    )
                }
                dropStaleResult()
                loadTransform()
            }
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
                // A desktop 3MF brings its plates.
                plateTools.plates(project.id).onSuccess(::showPlates)
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
        // Fewer filaments (another printer): nothing may print with one that is gone.
        val count = selection?.filaments?.size ?: 0
        if (project != null && count > 0 && count < (old.presets?.filaments?.size ?: 0)) {
            projects.filamentsChanged(project.id, null, count)
            _state.update { it.copy(plateRevision = it.plateRevision + 1) }
        }
        if (project != null && printerChanged) {
            val (x, y) = bed.center
            projects.centerOn(project.id, x, y)
            _state.update { it.copy(plateRevision = it.plateRevision + 1) }
        }
        if (project != null) dropAllResults()
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
                if (task == PlateTask.ARRANGE_ALL) {
                    plateTools.plates(project.id).onSuccess(::showPlates)
                    dropAllResults()
                }
                _state.update {
                    it.copy(
                        plateTask = null,
                        project = it.project?.copy(objects = update.objects),
                        selection = it.selection.takeIf { keepSelection }?.takeIf { ref -> update.objects.holds(ref) },
                        group = it.group.takeIf { keepSelection }.orEmpty().filter { ref -> update.objects.holds(ref) },
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
        PlateTask.SPLIT -> PlateNotice.Split(update.placed)
        PlateTask.ARRANGE_ALL -> PlateNotice.ArrangedAll(
            update.objects.flatMap { o -> (0 until o.instances).map(o::plateOf) }.distinct().size,
        ).takeIf { update.notPlaced == 0 } ?: PlateNotice.NotPlaced(update.notPlaced)
        PlateTask.ORIENT, PlateTask.DELETE, PlateTask.PLATES -> null
    }

    private fun List<ModelObjectInfo>.holds(ref: InstanceRef): Boolean =
        ref.instanceIndex < (getOrNull(ref.objectIndex)?.instances ?: 0)

    private fun startSlice() {
        val project = _state.value.project ?: return
        if (sliceJob?.isActive == true) return
        val plate = _state.value.currentPlate
        val named = plate.takeIf { _state.value.plates.size > 1 }
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
            sliceProject(project, _state.value.presets, named).collect { event ->
                val slice = event.toUiState()
                _state.update { it.copy(slice = slice) }
                if (slice is SliceUiState.Done) {
                    keepResult(plate, slice)
                    loadPreview(slice.gcodePath)
                }
            }
        }
    }

    /**
     * Slices every plate with something on it, one after another, showing each
     * on the plate as it goes; stops at the first that fails or is canceled.
     * Ends on the last plate sliced, with its preview.
     */
    private fun sliceAllPlates() {
        val start = _state.value
        val project = start.project ?: return
        if (sliceJob?.isActive == true || !start.canSwitchPlate) return
        val targets = start.platesToSlice
        if (targets.isEmpty()) return
        releasePreview()
        _state.update {
            it.copy(
                slice = SliceUiState.Running(0, "Preparing"),
                printHost = it.printHost.copy(status = PrintHostStatus.Idle),
                preview = PreviewUiState.None,
                showingPreview = false,
                selection = null,
                slicingAll = 1 to targets.size,
            )
        }
        loadTransform()
        sliceJob = viewModelScope.launch {
            var last: SliceUiState = SliceUiState.Idle
            try {
                for ((n, plate) in targets.withIndex()) {
                    if (plate != _state.value.currentPlate) {
                        val selected = plateTools.plates(project.id, PlateAction.Select(plate))
                        val plates = selected.getOrNull()
                        if (plates == null) {
                            last = SliceUiState.Failed(selected.exceptionOrNull()?.message ?: "Could not show plate ${plate + 1}")
                            break
                        }
                        showPlates(plates)
                    }
                    _state.update { it.copy(slicingAll = n + 1 to targets.size) }
                    sliceProject(project, _state.value.presets, plate).collect { event ->
                        last = event.toUiState()
                        _state.update { it.copy(slice = last) }
                    }
                    val done = last as? SliceUiState.Done ?: break
                    keepResult(plate, done)
                }
            } finally {
                // Canceled: cancelSlice() already said so.
                val end = last
                _state.update { it.copy(slicingAll = null, slice = if (end is SliceUiState.Running || end is SliceUiState.Idle) it.slice else end) }
            }
            (last as? SliceUiState.Done)?.let { loadPreview(it.gcodePath) }
        }
    }

    // --- prime tower --------------------------------------------------------

    /**
     * The current plate's prime tower, read again whenever what it depends on
     * changed: the objects, the plate, its settings, the presets or the paint.
     */
    private suspend fun followWipeTower() {
        combine(
            _state.map { Triple(it.project?.id, it.plateRevision, it.currentPlate) }.distinctUntilChanged(),
            _state.map { it.presets to it.plates.getOrNull(it.currentPlate)?.settings }.distinctUntilChanged(),
            towerInputs,
        ) { _, _, _ -> }.collect {
            val project = _state.value.project ?: return@collect
            towerJob?.cancel()
            towerJob = viewModelScope.launch {
                val tower = plateTools.wipeTower(project.id, _state.value.presets).getOrNull()
                _state.update { it.copy(wipeTower = tower) }
            }
        }
    }

    private fun Double.mm(): String = String.format(java.util.Locale.ROOT, "%.2f", this)

    // --- filaments ----------------------------------------------------------

    /** As the desktop's delete filament: the model's numbers follow before the slot goes. */
    private fun removeFilament(slot: Int) {
        val count = _state.value.choices.slots.size
        if (count <= 1 || slot !in 0 until count) return
        viewModelScope.launch {
            _state.value.project?.let { projects.filamentsChanged(it.id, slot, count - 1) }
            picker.removeFilament(slot)
            _state.update { it.copy(plateRevision = it.plateRevision + 1) }
        }
    }

    // --- plates -------------------------------------------------------------

    private fun keepResult(plate: Int, done: SliceUiState.Done) {
        plateResults[plate] = done
        _state.update { it.copy(slicedPlates = plateResults.keys.toSet()) }
    }

    /** Takes the engine's plates: the strip, the objects' plates, and the plate shown. */
    private fun showPlates(plates: Plates) {
        _state.update {
            val switched = plates.current != it.currentPlate
            // Redrawn when it shows something else: another plate, or objects that came or went.
            val redraw = switched || plates.objects != it.project?.objects
            it.copy(
                plates = plates.plates,
                currentPlate = plates.current,
                project = it.project?.copy(objects = plates.objects),
                selection = it.selection?.takeIf { ref ->
                    !switched && plates.objects.getOrNull(ref.objectIndex)?.plateOf(ref.instanceIndex) == plates.current
                },
                group = it.group.filter { ref ->
                    !switched && plates.objects.getOrNull(ref.objectIndex)?.plateOf(ref.instanceIndex) == plates.current
                },
                plateRevision = if (redraw) it.plateRevision + 1 else it.plateRevision,
            )
        }
    }

    /**
     * Runs a change to the plates in the engine, then shows them. Results of
     * the plates in [stale] are dropped; switching plates brings back the new
     * plate's own result, if it has one.
     */
    private fun changePlates(
        stale: Set<Int> = emptySet(),
        thenSelect: InstanceRef? = null,
        shiftResults: ((Int) -> Int?)? = null,
        notice: PlateNotice? = null,
        change: suspend (ProjectId) -> Plates,
    ) {
        val start = _state.value
        val project = start.project ?: return
        if (!start.canSwitchPlate) return
        _state.update { it.copy(plateTask = PlateTask.PLATES, plateNotice = null) }
        viewModelScope.launch {
            runCatching { change(project.id) }.onSuccess { plates ->
                val before = _state.value.currentPlate
                stale.forEach { plateResults.remove(it) }
                shiftResults?.let { shift ->
                    val kept = plateResults.mapNotNull { (k, v) -> shift(k)?.let { it to v } }.toMap()
                    plateResults.clear()
                    plateResults.putAll(kept)
                }
                if (before in stale || shiftResults != null || plates.current != before) showResultOf(plates.current)
                showPlates(plates)
                _state.update {
                    it.copy(
                        plateTask = null,
                        selection = thenSelect ?: it.selection,
                        slicedPlates = plateResults.keys.toSet(),
                        plateNotice = notice,
                    )
                }
                loadTransform()
            }.onFailure { error ->
                _state.update { it.copy(plateTask = null, plateNotice = PlateNotice.Failed(error.message ?: error.javaClass.simpleName)) }
            }
        }
    }

    private fun selectPlate(plate: Int, thenSelect: InstanceRef? = null) {
        if (plate == _state.value.currentPlate && thenSelect == null) return
        changePlates(thenSelect = thenSelect) { plateTools.plates(it, PlateAction.Select(plate)).getOrThrow() }
    }

    private fun removePlate(plate: Int) {
        if (_state.value.plates.size <= 1) return
        changePlates(stale = setOf(plate), shiftResults = { k -> if (k < plate) k else if (k > plate) k - 1 else null }) {
            plateTools.plates(it, PlateAction.Remove(plate)).getOrThrow()
        }
    }

    private fun moveSelectionToPlate(target: Int?) {
        val start = _state.value
        val selected = start.selected
        if (selected.isEmpty() || !start.plateEditable) return
        val from = start.currentPlate
        val to = target ?: start.plates.size
        if (to == from) return
        changePlates(stale = setOf(from, to), notice = PlateNotice.MovedToPlate(to)) { project ->
            if (target == null) plateTools.plates(project, PlateAction.Add).getOrThrow()
            var plates: Plates? = null
            for (ref in selected) plates = plateTools.plates(project, PlateAction.Move(ref, to)).getOrThrow()
            requireNotNull(plates)
        }
    }

    /**
     * A long press: the object joins the selection (becoming it when there is
     * none), or leaves it; the next selected one takes over as the main one.
     */
    private fun toggleSelected(instance: InstanceRef) {
        val s = _state.value
        if (!s.plateEditable || s.meshTool != null) return
        val plate = s.project?.objects?.getOrNull(instance.objectIndex)?.plateOf(instance.instanceIndex)
        if (plate != null && plate != s.currentPlate) return
        _state.update {
            when {
                it.selection == null -> it.copy(selection = instance, group = emptyList())
                it.selection == instance -> it.copy(selection = it.group.firstOrNull(), group = it.group.drop(1))
                instance in it.group -> it.copy(group = it.group - instance)
                else -> it.copy(group = it.group + instance)
            }
        }
        loadTransform()
    }

    /** The slice shown becomes [plate]'s own, if it kept one; its preview is read again. */
    private fun showResultOf(plate: Int) {
        releasePreview()
        val done = plateResults[plate]
        _state.update {
            it.copy(
                slice = done ?: SliceUiState.Idle,
                preview = PreviewUiState.None,
                showingPreview = false,
                printHost = it.printHost.copy(status = PrintHostStatus.Idle),
            )
        }
        done?.let { loadPreview(it.gcodePath) }
    }

    /** Counts the settings changes; a change after slicing makes the result stale, as moving an object does. */
    private suspend fun followSettingsChanges() {
        var previous: Map<String, String>? = null
        val presets = _state.map { it.presets }.distinctUntilChanged()
        combine(printSettings.edits, presets) { edits, selection ->
            if (edits.appliesTo(selection)) edits.values else emptyMap()
        }.distinctUntilChanged().collect { changes ->
            _state.update { it.copy(changedSettings = changes.size) }
            if (previous != null) dropAllResults()
            towerInputs.update { it + 1 }
            previous = changes
        }
    }

    /** Every plate's result: the presets or the settings changed. */
    private fun dropAllResults() {
        if (sliceJob?.isActive == true) return
        plateResults.clear()
        _state.update { it.copy(slicedPlates = emptySet()) }
        dropStaleResult()
    }

    /** The current plate changed: its result no longer matches it. */
    private fun dropStaleResult() {
        if (sliceJob?.isActive == true) return   // the running slice took its snapshot already
        if (plateResults.remove(_state.value.currentPlate) != null) {
            _state.update { it.copy(slicedPlates = plateResults.keys.toSet()) }
        }
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
                { PrintHostStatus.Failed(it.message ?: "Connection failed (${it.javaClass.simpleName})") },
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
                { PrintHostStatus.Failed(it.message ?: "Upload failed (${it.javaClass.simpleName})") },
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
