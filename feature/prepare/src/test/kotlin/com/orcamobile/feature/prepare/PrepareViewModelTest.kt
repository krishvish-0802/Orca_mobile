package com.orcamobile.feature.prepare

import androidx.lifecycle.SavedStateHandle
import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.model.WorkspaceRepository
import com.orcamobile.core.domain.preview.GCodePreviewRepository
import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.domain.printer.PrintHostClientFactory
import com.orcamobile.core.domain.project.ProjectRepository
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.domain.usecase.SendGcodeUseCase
import com.orcamobile.core.domain.usecase.SliceProjectUseCase
import com.orcamobile.core.domain.usecase.TestPrintHostUseCase
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.BuildPlateSide
import com.orcamobile.core.model.CompatiblePresets
import com.orcamobile.core.model.EngineStatus
import com.orcamobile.core.model.GCodePreviewSummary
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.ModelObjectInfo
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
        override suspend fun load(path: String) = result
        override suspend fun centerOn(id: ProjectId, x: Double, y: Double) { centers += x to y }
        override fun release(id: ProjectId) = Unit
    }

    private inner class FakePresets : PresetCatalog {
        override suspend fun vendors() = Result.success(listOf("Creality"))
        override suspend fun printers(vendor: String) = Result.success(listOf("Ender-3"))
        override suspend fun compatiblePresets(vendor: String, printer: String) =
            Result.success(CompatiblePresets(emptyList(), emptyList(), null, null))
        override suspend fun bedShape(vendor: String, printer: String) = Result.success(bed)
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
        override fun status(): EngineStatus = EngineStatus.Unavailable("fake")
        override fun slice(request: SliceRequest): Flow<SliceEvent> {
            lastRequest = request
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

    private fun viewModel(projects: ProjectRepository, settings: UserSettingsRepository) = PrepareViewModel(
        SavedStateHandle(mapOf(PREPARE_MODEL_PATH_ARG to "/models/benchy.stl")),
        projects,
        printSettings,
        previews,
        FakePresets(),
        settings,
        SliceProjectUseCase(engine, workspace, printSettings),
        TestPrintHostUseCase(hosts),
        SendGcodeUseCase(hosts),
    )

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
}
