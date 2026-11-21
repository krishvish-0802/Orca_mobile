package com.orcamobile.feature.home

import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.domain.printer.PrintHostClientFactory
import com.orcamobile.core.domain.model.SampleModelRepository
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.model.WorkspaceRepository
import com.orcamobile.core.domain.usecase.GetEngineStatusUseCase
import com.orcamobile.core.domain.usecase.SendGcodeUseCase
import com.orcamobile.core.domain.usecase.SliceSampleModelUseCase
import com.orcamobile.core.domain.usecase.TestPrintHostUseCase
import com.orcamobile.core.model.CompatiblePresets
import com.orcamobile.core.model.EngineInfo
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostInfo
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.model.EngineStatus
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceRequest
import com.orcamobile.core.model.SliceResult
import com.orcamobile.core.model.SliceStatistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val info = EngineInfo("OrcaSlicer", "2.6.0-dev", "02.08.01.55", "abc")

    /** An engine whose slice events the test pushes by hand. */
    private class ScriptedEngine(private val status: EngineStatus) : SlicingEngine {
        val events = MutableSharedFlow<SliceEvent>()
        override fun status() = status
        override fun slice(request: SliceRequest): Flow<SliceEvent> = events
    }

    private val samples = object : SampleModelRepository {
        override suspend fun calibrationCubePath() = "/cube.stl"
    }
    private val workspace = object : WorkspaceRepository {
        override fun newGcodePath(baseName: String) = "/$baseName.gcode"
    }

    private class FakePresets : PresetCatalog {
        override suspend fun vendors() = Result.success(listOf("Creality", "Prusa"))
        override suspend fun printers(vendor: String) = Result.success(listOf("$vendor P1", "$vendor P2"))
        override suspend fun compatiblePresets(vendor: String, printer: String) = Result.success(
            CompatiblePresets(listOf("Fine", "Standard"), listOf("PLA", "PETG"), "Standard", "PLA"),
        )
    }

    /** Records uploads; every host answers as a ready Klipper. */
    private class FakeHosts : PrintHostClientFactory {
        val uploads = mutableListOf<Pair<PrintHostConfig, String>>()
        override fun create(config: PrintHostConfig) = object : PrintHostClient {
            override suspend fun testConnection() = Result.success(PrintHostInfo("Klipper ready"))
            override suspend fun upload(
                localPath: String,
                remoteName: String,
                startPrint: Boolean,
                onProgress: (sent: Long, total: Long) -> Unit,
            ): Result<Unit> {
                onProgress(50, 100)
                uploads += config to localPath
                return Result.success(Unit)
            }
        }
    }

    private val hosts = FakeHosts()

    private class FakeSettings(
        var selection: PresetSelection? = null,
        var host: PrintHostConfig? = null,
    ) : UserSettingsRepository {
        override suspend fun presetSelection() = selection
        override suspend fun savePresetSelection(selection: PresetSelection) { this.selection = selection }
        override suspend fun printHost() = host
        override suspend fun savePrintHost(host: PrintHostConfig) { this.host = host }
    }

    private var settings = FakeSettings()

    private fun viewModel(engine: SlicingEngine) = HomeViewModel(
        GetEngineStatusUseCase(engine),
        SliceSampleModelUseCase(engine, samples, workspace),
        FakePresets(),
        TestPrintHostUseCase(hosts),
        SendGcodeUseCase(hosts),
        settings,
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `reports an unavailable engine`() {
        val vm = viewModel(ScriptedEngine(EngineStatus.Unavailable("not bundled")))
        assertEquals(EngineUiState.Unavailable("not bundled"), vm.state.value.engine)
    }

    @Test
    fun `follows a slice from progress to result`() = runTest {
        val engine = ScriptedEngine(EngineStatus.Available(info))
        val vm = viewModel(engine)
        assertEquals(EngineUiState.Ready(info), vm.state.value.engine)

        vm.onIntent(HomeIntent.SliceSample)
        engine.events.emit(SliceEvent.Progress(40, "Generating walls"))
        assertEquals(SliceUiState.Running(40, "Generating walls"), vm.state.value.slice)

        val stats = SliceStatistics(60.0, 1000.0, 3.0, 100, 2048)
        engine.events.emit(SliceEvent.Finished(SliceResult.Succeeded("/calibration_cube.gcode", stats)))
        assertEquals(SliceUiState.Done(stats, "/calibration_cube.gcode"), vm.state.value.slice)
    }

    @Test
    fun `cancel stops a running slice`() = runTest {
        val engine = ScriptedEngine(EngineStatus.Available(info))
        val vm = viewModel(engine)

        vm.onIntent(HomeIntent.SliceSample)
        assertTrue(vm.state.value.slice is SliceUiState.Running)
        vm.onIntent(HomeIntent.CancelSlice)
        assertEquals(SliceUiState.Canceled, vm.state.value.slice)

        // Late events from the cancelled slice no longer reach the state.
        engine.events.emit(SliceEvent.Progress(90, "late"))
        assertEquals(SliceUiState.Canceled, vm.state.value.slice)
    }

    @Test
    fun `choosing a printer fills in its recommended process and filament`() = runTest {
        val vm = viewModel(ScriptedEngine(EngineStatus.Available(info)))
        assertEquals(listOf("Creality", "Prusa"), vm.state.value.printerSetup.vendors)

        vm.onIntent(HomeIntent.SelectVendor("Creality"))
        assertEquals(listOf("Creality P1", "Creality P2"), vm.state.value.printerSetup.printers)

        vm.onIntent(HomeIntent.SelectPrinter("Creality P1"))
        assertEquals(
            PresetSelection("Creality", "Creality P1", "Standard", listOf("PLA")),
            vm.state.value.printerSetup.selection,
        )

        vm.onIntent(HomeIntent.SelectFilament("PETG"))
        assertEquals(listOf("PETG"), vm.state.value.printerSetup.selection?.filaments)
    }

    @Test
    fun `changing vendor clears the previous printer choice`() = runTest {
        val vm = viewModel(ScriptedEngine(EngineStatus.Available(info)))
        vm.onIntent(HomeIntent.SelectVendor("Creality"))
        vm.onIntent(HomeIntent.SelectPrinter("Creality P1"))

        vm.onIntent(HomeIntent.SelectVendor("Prusa"))
        val setup = vm.state.value.printerSetup
        assertEquals(null, setup.printer)
        assertEquals(null, setup.selection)
        assertEquals(listOf("Prusa P1", "Prusa P2"), setup.printers)
    }

    @Test
    fun `sends the sliced gcode to the configured host`() = runTest {
        val engine = ScriptedEngine(EngineStatus.Available(info))
        val vm = viewModel(engine)
        vm.onIntent(HomeIntent.SliceSample)
        engine.events.emit(
            SliceEvent.Finished(SliceResult.Succeeded("/out/cube.gcode", SliceStatistics(1.0, 1.0, 1.0, 1, 1))),
        )

        vm.onIntent(HomeIntent.SetHostAddress("192.168.1.50:7125"))
        vm.onIntent(HomeIntent.TestPrintHost)
        assertEquals(PrintHostStatus.Connected("Klipper ready"), vm.state.value.printHost.status)

        vm.onIntent(HomeIntent.SetStartPrint(true))
        vm.onIntent(HomeIntent.SendToPrinter)
        assertEquals(PrintHostStatus.Sent(printing = true), vm.state.value.printHost.status)
        assertEquals(
            listOf(PrintHostConfig(PrintHostType.MOONRAKER, "192.168.1.50:7125") to "/out/cube.gcode"),
            hosts.uploads,
        )
    }

    @Test
    fun `restores the saved printer setup and host`() = runTest {
        settings = FakeSettings(
            selection = PresetSelection("Prusa", "Prusa P2", "Fine", listOf("PETG")),
            host = PrintHostConfig(PrintHostType.OCTOPRINT, "octopi.local", "key"),
        )
        val vm = viewModel(ScriptedEngine(EngineStatus.Available(info)))

        assertEquals(PresetSelection("Prusa", "Prusa P2", "Fine", listOf("PETG")), vm.state.value.printerSetup.selection)
        assertEquals(PrintHostType.OCTOPRINT, vm.state.value.printHost.type)
        assertEquals("octopi.local", vm.state.value.printHost.address)
    }

    @Test
    fun `remembers the selection once a printer is chosen`() = runTest {
        val vm = viewModel(ScriptedEngine(EngineStatus.Available(info)))
        vm.onIntent(HomeIntent.SelectVendor("Creality"))
        vm.onIntent(HomeIntent.SelectPrinter("Creality P1"))
        vm.onIntent(HomeIntent.SelectProcess("Fine"))

        assertEquals(PresetSelection("Creality", "Creality P1", "Fine", listOf("PLA")), settings.selection)
    }
}
