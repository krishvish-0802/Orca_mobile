package com.orcamobile.feature.prepare

import androidx.lifecycle.SavedStateHandle
import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.model.WorkspaceRepository
import com.orcamobile.core.domain.presets.PresetPicker
import com.orcamobile.core.domain.presets.PresetSwitcher
import com.orcamobile.core.domain.presets.UnsavedChoice
import com.orcamobile.core.domain.preview.GCodePreviewRepository
import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.domain.printer.PrintHostClientFactory
import com.orcamobile.core.domain.project.PlateConfigResolver
import com.orcamobile.core.domain.project.PlateEditor
import com.orcamobile.core.domain.project.ProjectRepository
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.domain.usecase.PlateToolsUseCase
import com.orcamobile.core.domain.usecase.SendGcodeUseCase
import com.orcamobile.core.domain.usecase.SliceProjectUseCase
import com.orcamobile.core.domain.usecase.TestPrintHostUseCase
import com.orcamobile.core.model.ArrangeOptions
import com.orcamobile.core.model.Axes
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.BuildPlateSide
import com.orcamobile.core.model.CompatiblePresets
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.EngineStatus
import com.orcamobile.core.model.GCodePreviewSummary
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.InstanceTransform
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.PlateAction
import com.orcamobile.core.model.PlateConfig
import com.orcamobile.core.model.PlateInfo
import com.orcamobile.core.model.PlateSettingKeys
import com.orcamobile.core.model.PlateUpdate
import com.orcamobile.core.model.Plates
import com.orcamobile.core.model.PresetOptionKeys
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PreviewId
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostInfo
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.ProjectSummary
import com.orcamobile.core.model.SettingsEdits
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceRequest
import com.orcamobile.core.model.SliceResult
import com.orcamobile.core.model.SliceSource
import com.orcamobile.core.model.SliceStatistics
import com.orcamobile.core.model.ToolpathColoring
import com.orcamobile.core.model.TransformEdit
import com.orcamobile.core.model.TransformUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrepareViewModelTest {

    private val selection = PresetSelection("Creality", "Ender-3", "0.20mm Standard", listOf("PLA"))
    private val bed = BedShape(listOf(0.0 to 0.0, 300.0 to 0.0, 300.0 to 200.0, 0.0 to 200.0), 250.0)
    private val project = ProjectSummary(
        ProjectId("p1"),
        "/models/benchy.stl",
        listOf(ModelObjectInfo("benchy", 1, 225_000, listOf(60.0, 31.0, 48.0))),
    )

    private class FakeProjects(private val result: Result<ProjectSummary>) : ProjectRepository {
        val centers = mutableListOf<Pair<Double, Double>>()
        val history = mutableListOf<com.orcamobile.core.model.HistoryAction>()
        private var steps = 0
        private var at = -1

        override suspend fun history(id: ProjectId, action: com.orcamobile.core.model.HistoryAction): Result<com.orcamobile.core.model.HistoryState> {
            history += action
            val changed = when (action) {
                com.orcamobile.core.model.HistoryAction.RECORD -> { at += 1; steps = at + 1; false }
                com.orcamobile.core.model.HistoryAction.UNDO -> (at > 0).also { if (it) at -= 1 }
                com.orcamobile.core.model.HistoryAction.REDO -> (at < steps - 1).also { if (it) at += 1 }
            }
            return Result.success(
                com.orcamobile.core.model.HistoryState(result.getOrThrow().objects, changed, canUndo = at > 0, canRedo = at < steps - 1),
            )
        }
        override suspend fun load(path: String) = result
        override suspend fun centerOn(id: ProjectId, x: Double, y: Double) { centers += x to y }
        override fun release(id: ProjectId) = Unit
    }

    private inner class FakePresets : PresetCatalog {
        override suspend fun vendors() = Result.success(listOf("Creality"))
        override suspend fun printers(vendor: String) = Result.success(listOf("Ender-3", "K1 Max"))
        override suspend fun compatiblePresets(vendor: String, printer: String) =
            Result.success(CompatiblePresets(listOf("0.20mm Standard"), listOf("PLA"), "0.20mm Standard", "PLA"))
        override suspend fun bedShape(vendor: String, printer: String) =
            Result.success(if (printer == "K1 Max") BedShape(listOf(0.0 to 0.0, 300.0 to 300.0), 300.0) else bed)
    }

    private class FakeSettings(val selection: PresetSelection?) : UserSettingsRepository {
        var host: PrintHostConfig? = null
        override suspend fun presetSelection() = selection
        override suspend fun savePresetSelection(selection: PresetSelection) = Unit
        override suspend fun printHost() = host
        override suspend fun savePrintHost(host: PrintHostConfig) { this.host = host }
        var startOptions = PrintStartOptions()
        override suspend fun printStartOptions() = startOptions
        override suspend fun savePrintStartOptions(options: PrintStartOptions) { startOptions = options }
    }

    private class RecordingEngine : SlicingEngine {
        var lastRequest: SliceRequest? = null
        val requests = mutableListOf<SliceRequest>()
        override fun status(): EngineStatus = EngineStatus.Unavailable("fake")
        override fun slice(request: SliceRequest): Flow<SliceEvent> {
            lastRequest = request
            requests += request
            return flowOf(SliceEvent.Finished(SliceResult.Succeeded(request.outputGcodePath, SliceStatistics(60.0, 1.0, 1.0, 240, 1))))
        }
    }

    private val workspace = object : WorkspaceRepository {
        override fun newGcodePath(baseName: String) = "/gcode/$baseName.gcode"
    }

    private class FakeHosts : PrintHostClientFactory {
        val uploads = mutableListOf<String>()
        val options = mutableListOf<PrintStartOptions>()
        override fun create(config: PrintHostConfig) = object : PrintHostClient {
            override suspend fun testConnection() = Result.success(PrintHostInfo("Klipper ready"))
            override suspend fun upload(
                localPath: String,
                remoteName: String,
                startPrint: Boolean,
                options: PrintStartOptions,
                onProgress: (sent: Long, total: Long) -> Unit,
            ): Result<Unit> {
                uploads += localPath
                this@FakeHosts.options += options
                return Result.success(Unit)
            }
        }
    }

    private class FakePreviews : GCodePreviewRepository {
        val loaded = mutableListOf<String>()
        val released = mutableListOf<PreviewId>()
        override suspend fun load(gcodePath: String): Result<GCodePreviewSummary> {
            loaded += gcodePath
            val id = PreviewId("preview${loaded.size}")
            return Result.success(GCodePreviewSummary(id, gcodePath, List(240) { (it + 1) * 0.2f }, 60.0, 5000, listOf("#FF0000")))
        }
        override fun release(id: PreviewId) {
            released += id
        }
    }

    private class FakePrintSettings : PrintSettingsRepository {
        val state = MutableStateFlow(SettingsEdits.NONE)
        override val edits: Flow<SettingsEdits> = state
        override suspend fun editsFor(selection: PresetSelection?) = state.value.takeIf { it.appliesTo(selection) }?.values.orEmpty()
        override suspend fun set(selection: PresetSelection?, key: String, value: String) {
            state.value = SettingsEdits(selection, editsFor(selection) + (key to value))
        }
        override suspend fun reset(selection: PresetSelection?, key: String) {
            state.value = SettingsEdits(selection, editsFor(selection) - key)
        }
        override suspend fun resetAll() {
            state.value = SettingsEdits.NONE
        }
    }

    private val printSettings = FakePrintSettings()
    private val engine = RecordingEngine()
    private val hosts = FakeHosts()
    private val previews = FakePreviews()
    private val plateEditor = FakePlateEditor()

    /** Records the plate tools called; each returns [next], or fails with [failure]. */
    private class FakePlateEditor : PlateEditor {
        val calls = mutableListOf<String>()
        var configs = mutableListOf<PlateConfig>()
        var next = PlateUpdate(listOf(ModelObjectInfo("benchy", 1, 225_000, listOf(60.0, 31.0, 48.0))))
        var failure: Throwable? = null
        val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var hold: CompletableDeferred<Unit> = gate

        private suspend fun answer(call: String, config: PlateConfig? = null): Result<PlateUpdate> {
            calls += call
            config?.let { configs += it }
            hold.await()
            return failure?.let { Result.failure(it) } ?: Result.success(next)
        }

        override suspend fun arrange(project: ProjectId, config: PlateConfig, options: ArrangeOptions, allPlates: Boolean) =
            answer(if (allPlates) "arrange all" else "arrange spacing=${options.spacingMm} rotate=${options.allowRotation}", config)

        val plateList = mutableListOf(PlateInfo())
        var current = 0
        val plateCalls = mutableListOf<PlateAction>()

        override suspend fun plates(project: ProjectId, action: PlateAction): Result<Plates> {
            plateCalls += action
            when (action) {
                PlateAction.Get -> Unit
                is PlateAction.Select -> current = action.plate
                PlateAction.Add -> plateList += PlateInfo()
                is PlateAction.Remove -> {
                    plateList.removeAt(action.plate)
                    if (current >= action.plate && current > 0) current--
                }
                is PlateAction.Rename -> plateList[action.plate] = plateList[action.plate].copy(name = action.name)
                is PlateAction.SetSetting -> plateList[action.plate] =
                    plateList[action.plate].let { it.copy(settings = it.settings + (action.key to action.value)) }
                is PlateAction.ResetSetting -> plateList[action.plate] =
                    plateList[action.plate].let { it.copy(settings = it.settings - action.key) }
                is PlateAction.Move -> next = next.copy(
                    objects = next.objects.mapIndexed { i, o ->
                        if (i != action.instance.objectIndex) {
                            o
                        } else {
                            o.copy(plates = List(o.instances) { k -> if (k == action.instance.instanceIndex) action.plate else o.plateOf(k) })
                        }
                    },
                )
            }
            return Result.success(Plates(current, plateList.toList(), next.objects))
        }

        override suspend fun orient(project: ProjectId, instance: InstanceRef?, config: PlateConfig) =
            answer("orient $instance", config)

        override suspend fun align(
            project: ProjectId,
            instances: List<InstanceRef>,
            mode: com.orcamobile.core.model.AlignMode,
            toPlate: Boolean,
            config: PlateConfig,
        ) = answer("align $mode toPlate=$toPlate ${instances.size}", config)

        override suspend fun addCopies(
            project: ProjectId,
            instance: InstanceRef,
            count: Int?,
            config: PlateConfig,
            options: ArrangeOptions,
        ) = answer("copies $instance $count", config)

        override suspend fun remove(project: ProjectId, instance: InstanceRef) = answer("remove $instance")

        override suspend fun splitToObjects(project: ProjectId, objectIndex: Int) = answer("split $objectIndex")

        val edits = mutableListOf<TransformEdit?>()
        var transform = InstanceTransform(
            position = Axes(110.0, 110.0, 24.0),
            rotationDegrees = Axes(0.0, 0.0, 0.0),
            scale = Axes(1.0, 1.0, 1.0),
            size = Axes(60.0, 31.0, 48.0),
            mirrored = emptySet(),
        )

        override suspend fun transform(project: ProjectId, instance: InstanceRef, edit: TransformEdit?): Result<TransformUpdate> {
            edits += edit
            if (edit is TransformEdit.Scale) transform = transform.copy(scale = edit.factors)
            return Result.success(TransformUpdate(transform, next.objects))
        }
    }


    /** Which settings each kind of preset holds; the presets give the defaults. */
    private val optionCatalog = object : ConfigOptionCatalog {
        override suspend fun describe(keys: List<String>) = Result.success(emptyList<ConfigOption>())
        override suspend fun values(selection: PresetSelection?, keys: List<String>) =
            Result.success(mapOf("layer_height" to "0.2").filterKeys { it in keys })
        override suspend fun presetOptionKeys() = Result.success(PresetOptionKeys(listOf("layer_height"), emptyList(), emptyList()))
    }

    private fun viewModel(projects: ProjectRepository, settings: UserSettingsRepository): PrepareViewModel {
        val picker = PresetPicker(FakePresets(), settings)
        return PrepareViewModel(
            SavedStateHandle(mapOf(PREPARE_MODEL_PATH_ARG to "/models/benchy.stl")),
            projects,
            printSettings,
            previews,
            FakePresets(),
            picker,
            settings,
            SliceProjectUseCase(engine, workspace, PlateConfigResolver(printSettings)),
            PlateToolsUseCase(plateEditor, PlateConfigResolver(printSettings)),
            TestPrintHostUseCase(hosts),
            SendGcodeUseCase(hosts),
            PresetSwitcher(picker, printSettings, optionCatalog),
        )
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opens the model on the chosen printer's bed, centered`() {
        val projects = FakeProjects(Result.success(project))
        val vm = viewModel(projects, FakeSettings(selection))

        val state = vm.state.value
        assertEquals(false, state.loading)
        assertEquals(project, state.project)
        assertEquals(bed, state.bed)
        assertEquals(listOf(150.0 to 100.0), projects.centers)
    }

    @Test
    fun `falls back to a generic bed without a printer`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(null))
        assertEquals(PrepareViewModel.DEFAULT_BED, vm.state.value.bed)
    }

    @Test
    fun `shows why a model could not be opened`() {
        val vm = viewModel(FakeProjects(Result.failure(IllegalStateException("Not a mesh"))), FakeSettings(selection))
        assertEquals("Not a mesh", vm.state.value.error)
        assertEquals(null, vm.state.value.project)
    }

    @Test
    fun `slices the project as placed and sends the result`() {
        val settings = FakeSettings(selection)
        val vm = viewModel(FakeProjects(Result.success(project)), settings)

        vm.onIntent(PrepareIntent.Slice)
        val request = requireNotNull(engine.lastRequest)
        assertEquals(SliceSource.Project(ProjectId("p1")), request.source)
        assertEquals(selection, request.presets)
        assertEquals("/gcode/benchy.gcode", request.outputGcodePath)
        assertIs<SliceUiState.Done>(vm.state.value.slice)

        vm.onIntent(PrepareIntent.SetHostAddress("printer.local"))
        vm.onIntent(PrepareIntent.SendToPrinter)
        assertEquals(PrintHostStatus.Sent(printing = false), vm.state.value.printHost.status)
        assertEquals(listOf("/gcode/benchy.gcode"), hosts.uploads)
        assertEquals("printer.local", settings.host?.baseUrl)
    }

    @Test
    fun `shows the sliced toolpaths with every layer once slicing is done`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.Slice)

        assertEquals(listOf("/gcode/benchy.gcode"), previews.loaded)
        val preview = assertIs<PreviewUiState.Ready>(vm.state.value.preview)
        assertEquals(0..239, preview.layers)
        assertEquals(true, vm.state.value.showingPreview)

        vm.onIntent(PrepareIntent.ShowPreview(false))
        assertEquals(false, vm.state.value.showingPreview)
    }

    @Test
    fun `layer range and coloring are kept within the preview`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.Slice)

        vm.onIntent(PrepareIntent.SetPreviewLayers(10..500))
        vm.onIntent(PrepareIntent.SetPreviewColoring(ToolpathColoring.Speed))
        val preview = assertIs<PreviewUiState.Ready>(vm.state.value.preview)
        assertEquals(10..239, preview.layers)
        assertEquals(ToolpathColoring.Speed, preview.coloring)
    }

    @Test
    fun `slicing again frees the previous preview`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.Slice)
        vm.onIntent(PrepareIntent.Slice)

        assertEquals(listOf(PreviewId("preview1")), previews.released)
        assertEquals(PreviewId("preview2"), assertIs<PreviewUiState.Ready>(vm.state.value.preview).summary.id)
    }

    @Test
    fun `the preview cannot be shown before slicing`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.ShowPreview(true))
        assertEquals(false, vm.state.value.showingPreview)
    }

    @Test
    fun `editing the plate after slicing drops the stale result`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.Select(InstanceRef(0, 0)))
        vm.onIntent(PrepareIntent.Slice)

        vm.onIntent(PrepareIntent.PlateEdited)
        val state = vm.state.value
        assertEquals(SliceUiState.Idle, state.slice)
        assertEquals(PreviewUiState.None, state.preview)
        assertEquals(listOf(PreviewId("preview1")), previews.released)
        assertEquals(InstanceRef(0, 0), state.selection)
    }

    @Test
    fun `start options are remembered and sent with the print`() {
        val settings = FakeSettings(selection).apply { startOptions = PrintStartOptions(timeLapse = true) }
        val vm = viewModel(FakeProjects(Result.success(project)), settings)
        assertEquals(PrintStartOptions(timeLapse = true), vm.state.value.printHost.startOptions)

        vm.onIntent(PrepareIntent.Slice)
        vm.onIntent(PrepareIntent.SetHostType(PrintHostType.ELEGOO_LINK))
        vm.onIntent(PrepareIntent.SetHostAddress("192.168.1.64"))
        vm.onIntent(PrepareIntent.SetStartPrint(true))
        vm.onIntent(PrepareIntent.SetHeatedBedLeveling(true))
        vm.onIntent(PrepareIntent.SetBuildPlate(BuildPlateSide.SMOOTH))
        vm.onIntent(PrepareIntent.SendToPrinter)

        val expected = PrintStartOptions(timeLapse = true, heatedBedLeveling = true, plate = BuildPlateSide.SMOOTH)
        assertEquals(listOf(expected), hosts.options)
        assertEquals(expected, settings.startOptions)
    }

    @Test
    fun `changing a setting after slicing drops the stale result and slices with the change`() = kotlinx.coroutines.test.runTest {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.Slice)
        assertIs<SliceUiState.Done>(vm.state.value.slice)

        printSettings.set(selection, "layer_height", "0.12")
        assertEquals(SliceUiState.Idle, vm.state.value.slice)
        assertEquals(1, vm.state.value.changedSettings)

        vm.onIntent(PrepareIntent.Slice)
        assertEquals(mapOf("layer_height" to "0.12"), engine.lastRequest?.configOverrides)
    }

    @Test
    fun `choosing another printer on the plate brings its bed and re-places the model`() {
        val projects = FakeProjects(Result.success(project))
        val vm = viewModel(projects, FakeSettings(selection))
        vm.onIntent(PrepareIntent.Slice)
        assertIs<SliceUiState.Done>(vm.state.value.slice)

        vm.onIntent(PrepareIntent.SelectPrinter("K1 Max"))

        val state = vm.state.value
        assertEquals("K1 Max", state.presets?.printer)
        assertEquals(300.0, state.bed?.maxHeight)
        assertEquals(150.0 to 150.0, projects.centers.last())
        assertEquals(1, state.plateRevision)
        assertEquals(SliceUiState.Idle, state.slice)
    }

    @Test
    fun `another process asks about the changes to the one left, and keeps or drops them`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        kotlinx.coroutines.runBlocking { printSettings.set(selection, "layer_height", "0.12") }

        vm.onIntent(PrepareIntent.SelectProcess("0.12mm Fine"))
        val prompt = assertNotNull(vm.state.value.unsaved)
        assertEquals(1, prompt.changes)
        assertEquals(selection.process, vm.state.value.presets?.process)

        vm.onIntent(PrepareIntent.ResolveUnsaved(UnsavedChoice.Keep))
        assertNull(vm.state.value.unsaved)
        assertEquals("0.12mm Fine", vm.state.value.presets?.process)
        assertEquals(mapOf("layer_height" to "0.12"), printSettings.state.value.values)
        assertEquals("0.12mm Fine", printSettings.state.value.selection?.process)

        vm.onIntent(PrepareIntent.SelectProcess(selection.process))
        vm.onIntent(PrepareIntent.ResolveUnsaved(UnsavedChoice.Discard))
        assertEquals(selection.process, vm.state.value.presets?.process)
        assertEquals(emptyMap(), printSettings.state.value.values)
    }

    @Test
    fun `another process keeps the bed and the placement`() {
        val projects = FakeProjects(Result.success(project))
        val vm = viewModel(projects, FakeSettings(selection))
        vm.onIntent(PrepareIntent.SelectProcess("0.12mm Fine"))
        assertEquals("0.12mm Fine", vm.state.value.presets?.process)
        assertEquals(1, projects.centers.size)
        assertEquals(0, vm.state.value.plateRevision)
    }

    @Test
    fun `arrange runs with the slice's presets and the chosen spacing, then redraws the plate`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.SetArrangeOptions(ArrangeOptions(spacingMm = 5.0, allowRotation = true)))
        vm.onIntent(PrepareIntent.Arrange)

        assertEquals(listOf("arrange spacing=5.0 rotate=true"), plateEditor.calls)
        assertEquals(selection, plateEditor.configs.single().presets)
        val state = vm.state.value
        assertEquals(1, state.plateRevision)
        assertNull(state.plateTask)
        assertNull(state.plateNotice)
    }

    @Test
    fun `objects that did not fit are reported`() {
        plateEditor.next = plateEditor.next.copy(placed = 1, notPlaced = 2)
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.Arrange)
        assertEquals(PlateNotice.NotPlaced(2), vm.state.value.plateNotice)
        vm.onIntent(PrepareIntent.DismissPlateNotice)
        assertNull(vm.state.value.plateNotice)
    }

    @Test
    fun `orient turns the selection, or everything without one`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.Orient)
        vm.onIntent(PrepareIntent.Select(InstanceRef(0, 0)))
        vm.onIntent(PrepareIntent.Orient)
        assertEquals(listOf("orient null", "orient InstanceRef(objectIndex=0, instanceIndex=0)"), plateEditor.calls)
        assertEquals(InstanceRef(0, 0), vm.state.value.selection)
    }

    @Test
    fun `copies and fill bed need a selection and update the object list`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.AddCopies(3))
        assertEquals(emptyList(), plateEditor.calls)

        vm.onIntent(PrepareIntent.Select(InstanceRef(0, 0)))
        plateEditor.next = PlateUpdate(listOf(ModelObjectInfo("benchy", 4, 225_000, listOf(60.0, 31.0, 48.0))), placed = 3)
        vm.onIntent(PrepareIntent.AddCopies(3))
        assertEquals(4, vm.state.value.project?.objects?.single()?.instances)
        assertEquals(PlateNotice.CopiesAdded(3, 0), vm.state.value.plateNotice)

        plateEditor.next = plateEditor.next.copy(placed = 0)
        vm.onIntent(PrepareIntent.AddCopies(null))
        assertEquals("copies InstanceRef(objectIndex=0, instanceIndex=0) null", plateEditor.calls.last())
        assertEquals(PlateNotice.BedFull, vm.state.value.plateNotice)
    }

    @Test
    fun `deleting clears the selection and drops the sliced result`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.Slice)
        assertIs<SliceUiState.Done>(vm.state.value.slice)
        // The toolpath preview has no plate tools; back on the model they work.
        if (vm.state.value.showingPreview) {
            assertFalse(vm.state.value.plateEditable)
            vm.onIntent(PrepareIntent.ShowPreview(false))
        }
        vm.onIntent(PrepareIntent.Select(InstanceRef(0, 0)))

        plateEditor.next = PlateUpdate(emptyList())
        vm.onIntent(PrepareIntent.DeleteSelected)

        val state = vm.state.value
        assertNull(state.selection)
        assertEquals(emptyList(), state.project?.objects)
        assertEquals(SliceUiState.Idle, state.slice)
    }

    @Test
    fun `one plate tool at a time, and the plate is locked while it runs`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        plateEditor.hold = CompletableDeferred()
        vm.onIntent(PrepareIntent.Arrange)
        assertEquals(PlateTask.ARRANGE, vm.state.value.plateTask)
        assertFalse(vm.state.value.plateEditable)
        vm.onIntent(PrepareIntent.Orient)
        assertEquals(1, plateEditor.calls.size)

        plateEditor.hold.complete(Unit)
        assertNull(vm.state.value.plateTask)
        assertTrue(vm.state.value.plateEditable)
    }

    @Test
    fun `a failing tool says why and leaves the plate as it was`() {
        plateEditor.failure = IllegalStateException("Objects could not fit")
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.Arrange)
        assertEquals(PlateNotice.Failed("Objects could not fit"), vm.state.value.plateNotice)
        assertEquals(0, vm.state.value.plateRevision)
    }

    @Test
    fun `selecting an object shows its transform, and edits apply to it`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        assertNull(vm.state.value.transform)
        vm.onIntent(PrepareIntent.Select(InstanceRef(0, 0)))
        assertEquals(Axes(60.0, 31.0, 48.0), vm.state.value.transform?.size)

        vm.onIntent(PrepareIntent.EditTransform(TransformEdit.Scale(Axes(2.0, 2.0, 2.0))))
        assertEquals(Axes(2.0, 2.0, 2.0), vm.state.value.transform?.scale)
        assertEquals(1, vm.state.value.plateRevision)

        vm.onIntent(PrepareIntent.Select(null))
        assertNull(vm.state.value.transform)
        assertEquals(listOf<TransformEdit?>(null, TransformEdit.Scale(Axes(2.0, 2.0, 2.0))), plateEditor.edits)
    }

    @Test
    fun `a drag on the plate refreshes the transform`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.Select(InstanceRef(0, 0)))
        vm.onIntent(PrepareIntent.PlateEdited)
        assertEquals(listOf<TransformEdit?>(null, null), plateEditor.edits)
    }

    @Test
    fun `lay flat is a mode that ends with the object laid and selected`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.SetLayOnFace(true))
        assertTrue(vm.state.value.layingOnFace)

        vm.onIntent(PrepareIntent.LaidOnFace(InstanceRef(0, 0)))
        val state = vm.state.value
        assertFalse(state.layingOnFace)
        assertEquals(InstanceRef(0, 0), state.selection)
        assertEquals(Axes(60.0, 31.0, 48.0), state.transform?.size)
    }

    @Test
    fun `the handle mode is remembered for the plate`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        assertEquals(com.orcamobile.core.viewport.HandleMode.MOVE, vm.state.value.handleMode)
        vm.onIntent(PrepareIntent.SetHandleMode(com.orcamobile.core.viewport.HandleMode.ROTATE))
        assertEquals(com.orcamobile.core.viewport.HandleMode.ROTATE, vm.state.value.handleMode)
    }

    @Test
    fun `painting needs a selected object and pauses the other plate tools`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.StartPainting(com.orcamobile.core.model.PaintKind.SEAM))
        assertNull(vm.state.value.paint)

        vm.onIntent(PrepareIntent.Select(InstanceRef(0, 0)))
        vm.onIntent(PrepareIntent.StartPainting(com.orcamobile.core.model.PaintKind.SEAM))
        assertEquals(com.orcamobile.core.model.PaintKind.SEAM, vm.state.value.paint?.kind)
        assertFalse(vm.state.value.plateEditable)
        vm.onIntent(PrepareIntent.Arrange)
        assertEquals(emptyList(), plateEditor.calls)

        vm.onIntent(PrepareIntent.SetBrush(com.orcamobile.core.model.PaintBrush(com.orcamobile.core.model.PaintKind.SEAM, radiusMm = 5f)))
        assertEquals(5f, vm.state.value.paint?.radiusMm)
        vm.onIntent(PrepareIntent.ClearPaint)
        assertEquals(1, vm.state.value.paintClearRequest)

        vm.onIntent(PrepareIntent.StopPainting)
        assertNull(vm.state.value.paint)
        assertTrue(vm.state.value.plateEditable)
    }

    @Test
    fun `a stroke makes an earlier slice stale`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.Slice)
        vm.onIntent(PrepareIntent.Painted)
        assertEquals(SliceUiState.Idle, vm.state.value.slice)
    }

    @Test
    fun `colour painting is offered only with more than one filament`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        assertTrue(com.orcamobile.core.model.PaintKind.COLOR !in vm.state.value.paintKinds)
    }

    @Test
    fun `split to objects reports how many it made and clears the selection`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.Select(InstanceRef(0, 0)))
        plateEditor.next = PlateUpdate(
            listOf(ModelObjectInfo("benchy_1", 1, 100, listOf(10.0, 10.0, 10.0)), ModelObjectInfo("benchy_2", 1, 100, listOf(10.0, 10.0, 10.0))),
            placed = 2,
        )
        vm.onIntent(PrepareIntent.SplitToObjects)
        assertEquals("split 0", plateEditor.calls.single())
        assertEquals(PlateNotice.Split(2), vm.state.value.plateNotice)
        assertNull(vm.state.value.selection)
        assertEquals(2, vm.state.value.project?.objects?.size)
    }

    // --- plates -------------------------------------------------------------

    /** A benchy with one instance on each of two plates. */
    private fun twoPlates(): ProjectSummary {
        val benchy = ModelObjectInfo("benchy", 2, 225_000, listOf(60.0, 31.0, 48.0), plates = listOf(0, 1))
        plateEditor.next = PlateUpdate(listOf(benchy))
        plateEditor.plateList += PlateInfo()
        return project.copy(objects = listOf(benchy))
    }

    @Test
    fun `the project's plates are read on opening`() {
        val vm = viewModel(FakeProjects(Result.success(twoPlates())), FakeSettings(selection))
        assertEquals(PlateAction.Get, plateEditor.plateCalls.single())
        assertEquals(2, vm.state.value.plates.size)
        assertEquals(1, vm.state.value.instancesOn(1))
        assertEquals(listOf(0, 1), vm.state.value.platesToSlice)
    }

    @Test
    fun `each plate keeps its own slice while it matches the plate`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.Slice)
        assertEquals(setOf(0), vm.state.value.slicedPlates)

        vm.onIntent(PrepareIntent.AddPlate)
        assertEquals(1, vm.state.value.currentPlate)
        assertEquals(SliceUiState.Idle, vm.state.value.slice)
        assertEquals(PreviewUiState.None, vm.state.value.preview)

        vm.onIntent(PrepareIntent.SelectPlate(0))
        assertEquals("/gcode/benchy.gcode", assertIs<SliceUiState.Done>(vm.state.value.slice).gcodePath)
        assertEquals(listOf("/gcode/benchy.gcode", "/gcode/benchy.gcode"), previews.loaded)

        vm.onIntent(PrepareIntent.PlateEdited)
        assertEquals(emptySet(), vm.state.value.slicedPlates)
    }

    @Test
    fun `with several plates the G-code is named after the plate`() {
        val vm = viewModel(FakeProjects(Result.success(twoPlates())), FakeSettings(selection))
        vm.onIntent(PrepareIntent.SelectPlate(1))
        vm.onIntent(PrepareIntent.Slice)
        assertEquals("/gcode/benchy_plate2.gcode", engine.lastRequest?.outputGcodePath)
    }

    @Test
    fun `slice all slices every plate in turn and ends on the last`() {
        val vm = viewModel(FakeProjects(Result.success(twoPlates())), FakeSettings(selection))
        vm.onIntent(PrepareIntent.SliceAll)

        assertEquals(listOf("/gcode/benchy_plate1.gcode", "/gcode/benchy_plate2.gcode"), engine.requests.map { it.outputGcodePath })
        val state = vm.state.value
        assertEquals(setOf(0, 1), state.slicedPlates)
        assertEquals(1, state.currentPlate)
        assertNull(state.slicingAll)
        assertEquals("/gcode/benchy_plate2.gcode", assertIs<SliceUiState.Done>(state.slice).gcodePath)
        assertIs<PreviewUiState.Ready>(state.preview)
    }

    @Test
    fun `a plate's own setting makes only that plate's slice stale`() {
        val vm = viewModel(FakeProjects(Result.success(twoPlates())), FakeSettings(selection))
        vm.onIntent(PrepareIntent.SliceAll)

        vm.onIntent(PrepareIntent.SetPlateSetting(0, PlateSettingKeys.BED_TYPE, "Textured PEI Plate"))
        assertEquals("Textured PEI Plate", vm.state.value.plates[0].settings[PlateSettingKeys.BED_TYPE])
        assertEquals(setOf(1), vm.state.value.slicedPlates)
        assertIs<SliceUiState.Done>(vm.state.value.slice)

        vm.onIntent(PrepareIntent.SetPlateSetting(1, PlateSettingKeys.SPIRAL_VASE, "1"))
        assertEquals(emptySet(), vm.state.value.slicedPlates)
        assertEquals(SliceUiState.Idle, vm.state.value.slice)

        vm.onIntent(PrepareIntent.SetPlateSetting(1, PlateSettingKeys.SPIRAL_VASE, null))
        assertEquals(PlateAction.ResetSetting(1, PlateSettingKeys.SPIRAL_VASE), plateEditor.plateCalls.last())
    }

    @Test
    fun `selecting an object of another plate shows that plate`() {
        val vm = viewModel(FakeProjects(Result.success(twoPlates())), FakeSettings(selection))
        val revision = vm.state.value.plateRevision
        vm.onIntent(PrepareIntent.Select(InstanceRef(0, 1)))
        assertEquals(1, vm.state.value.currentPlate)
        assertEquals(InstanceRef(0, 1), vm.state.value.selection)
        assertTrue(vm.state.value.plateRevision > revision)
    }

    @Test
    fun `the selection can go to a new plate`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.Select(InstanceRef(0, 0)))
        vm.onIntent(PrepareIntent.MoveToPlate(null))

        assertEquals(listOf(PlateAction.Add, PlateAction.Move(InstanceRef(0, 0), 1)), plateEditor.plateCalls.drop(1))
        assertEquals(0, vm.state.value.currentPlate)
        assertNull(vm.state.value.selection)
        assertEquals(1, vm.state.value.instancesOn(1))
        assertEquals(PlateNotice.MovedToPlate(1), vm.state.value.plateNotice)
    }

    @Test
    fun `removing a plate keeps the later plates' slices`() {
        val vm = viewModel(FakeProjects(Result.success(twoPlates())), FakeSettings(selection))
        vm.onIntent(PrepareIntent.SliceAll)
        vm.onIntent(PrepareIntent.RemovePlate(0))
        assertEquals(1, vm.state.value.plates.size)
        assertEquals(0, vm.state.value.currentPlate)
        assertEquals(setOf(0), vm.state.value.slicedPlates)
        assertEquals("/gcode/benchy_plate2.gcode", assertIs<SliceUiState.Done>(vm.state.value.slice).gcodePath)
    }

    @Test
    fun `arrange all plates reads the plates back and drops every slice`() {
        val vm = viewModel(FakeProjects(Result.success(twoPlates())), FakeSettings(selection))
        vm.onIntent(PrepareIntent.SliceAll)
        vm.onIntent(PrepareIntent.ShowPreview(false))   // the plate tools work on the model
        vm.onIntent(PrepareIntent.ArrangeAllPlates)
        assertEquals("arrange all", plateEditor.calls.single())
        assertEquals(PlateAction.Get, plateEditor.plateCalls.last())
        assertEquals(emptySet(), vm.state.value.slicedPlates)
        assertEquals(PlateNotice.ArrangedAll(2), vm.state.value.plateNotice)
    }

    @Test
    fun `a new painted modifier is painted on once its first stroke made it`() {
        val vm = viewModel(FakeProjects(Result.success(project)), FakeSettings(selection))
        vm.onIntent(PrepareIntent.Select(InstanceRef(0, 0)))
        vm.onIntent(PrepareIntent.StartPainting(com.orcamobile.core.model.PaintKind.PAINTED_MODIFIER))
        assertNull(vm.state.value.paint?.modifier)
        vm.onIntent(PrepareIntent.PaintedModifierMade(1))
        assertEquals(1, vm.state.value.paint?.modifier)

        vm.onIntent(PrepareIntent.StopPainting)
        vm.onIntent(PrepareIntent.StartPainting(com.orcamobile.core.model.PaintKind.PAINTED_MODIFIER, modifier = 3))
        assertEquals(3, vm.state.value.paint?.modifier)
    }

    @Test
    fun `a long press adds objects to the selection, and tools take them all`() {
        val two = ModelObjectInfo("benchy", 3, 225_000, listOf(60.0, 31.0, 48.0))
        plateEditor.next = PlateUpdate(listOf(two))
        val vm = viewModel(FakeProjects(Result.success(project.copy(objects = listOf(two)))), FakeSettings(selection))
        vm.onIntent(PrepareIntent.ToggleSelected(InstanceRef(0, 0)))
        vm.onIntent(PrepareIntent.ToggleSelected(InstanceRef(0, 1)))
        vm.onIntent(PrepareIntent.ToggleSelected(InstanceRef(0, 2)))
        assertEquals(InstanceRef(0, 0), vm.state.value.selection)
        assertEquals(listOf(InstanceRef(0, 1), InstanceRef(0, 2)), vm.state.value.group)

        vm.onIntent(PrepareIntent.Align(com.orcamobile.core.model.AlignMode.LEFT, toPlate = false))
        assertEquals("align LEFT toPlate=false 3", plateEditor.calls.last())
        assertEquals(2, vm.state.value.group.size)

        // Taking the main one out: the next takes over.
        vm.onIntent(PrepareIntent.ToggleSelected(InstanceRef(0, 0)))
        assertEquals(InstanceRef(0, 1), vm.state.value.selection)
        assertEquals(listOf(InstanceRef(0, 2)), vm.state.value.group)

        // Delete goes from the last instance to the first.
        vm.onIntent(PrepareIntent.DeleteSelected)
        assertEquals(listOf("remove InstanceRef(objectIndex=0, instanceIndex=2)", "remove InstanceRef(objectIndex=0, instanceIndex=1)"), plateEditor.calls.takeLast(2))
        assertNull(vm.state.value.selection)
        assertEquals(emptyList(), vm.state.value.group)

        // A plain tap selects one again.
        vm.onIntent(PrepareIntent.ToggleSelected(InstanceRef(0, 0)))
        vm.onIntent(PrepareIntent.ToggleSelected(InstanceRef(0, 1)))
        vm.onIntent(PrepareIntent.Select(InstanceRef(0, 2)))
        assertEquals(emptyList(), vm.state.value.group)
    }

    @Test
    fun `changes can be undone and redone`() {
        val projects = FakeProjects(Result.success(project))
        val vm = viewModel(projects, FakeSettings(selection))
        assertEquals(listOf(com.orcamobile.core.model.HistoryAction.RECORD), projects.history)
        assertFalse(vm.state.value.canUndo)

        vm.onIntent(PrepareIntent.Arrange)
        assertTrue(vm.state.value.canUndo)
        vm.onIntent(PrepareIntent.Select(InstanceRef(0, 0)))
        val revision = vm.state.value.plateRevision

        vm.onIntent(PrepareIntent.Undo)
        assertFalse(vm.state.value.canUndo)
        assertTrue(vm.state.value.canRedo)
        assertNull(vm.state.value.selection)
        assertTrue(vm.state.value.plateRevision > revision)

        vm.onIntent(PrepareIntent.Redo)
        assertTrue(vm.state.value.canUndo)
        assertFalse(vm.state.value.canRedo)
        assertNull(vm.state.value.plateTask)
    }

    @Test
    fun `undo works in the Text tool and tells it the project changed`() {
        val projects = FakeProjects(Result.success(project))
        val vm = viewModel(projects, FakeSettings(selection))
        vm.onIntent(PrepareIntent.Arrange)
        vm.onIntent(PrepareIntent.OpenMeshTool(MeshTool.EMBOSS))
        assertTrue(vm.state.value.historyUsable)
        val revision = vm.state.value.historyRevision

        vm.onIntent(PrepareIntent.Undo)
        assertTrue(vm.state.value.canRedo)
        assertEquals(revision + 1, vm.state.value.historyRevision)
        assertEquals(MeshTool.EMBOSS, vm.state.value.meshTool)

        vm.onIntent(PrepareIntent.OpenMeshTool(MeshTool.MEASURE))
        assertFalse(vm.state.value.historyUsable)
    }
}
