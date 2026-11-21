package com.orcamobile.feature.home

import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.domain.model.ModelImportRepository
import com.orcamobile.core.domain.model.SampleModelRepository
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.usecase.GetEngineStatusUseCase
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.CompatiblePresets
import com.orcamobile.core.model.EngineInfo
import com.orcamobile.core.model.EngineStatus
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val info = EngineInfo("OrcaSlicer", "2.6.0-dev", "02.08.01.55", "abc")

    private class FixedEngine(private val status: EngineStatus) : SlicingEngine {
        override fun status() = status
        override fun slice(request: SliceRequest): Flow<SliceEvent> = emptyFlow()
    }

    private class FakePresets : PresetCatalog {
        override suspend fun vendors() = Result.success(listOf("Creality", "Prusa"))
        override suspend fun printers(vendor: String) = Result.success(listOf("$vendor P1", "$vendor P2"))
        override suspend fun compatiblePresets(vendor: String, printer: String) = Result.success(
            CompatiblePresets(listOf("Fine", "Standard"), listOf("PLA", "PETG"), "Standard", "PLA"),
        )
        override suspend fun bedShape(vendor: String, printer: String) =
            Result.success(BedShape(listOf(0.0 to 0.0, 220.0 to 220.0), 250.0))
    }

    private class FakeSettings(var selection: PresetSelection? = null) : UserSettingsRepository {
        override suspend fun presetSelection() = selection
        override suspend fun savePresetSelection(selection: PresetSelection) { this.selection = selection }
        override suspend fun printHost(): PrintHostConfig? = null
        override suspend fun savePrintHost(host: PrintHostConfig) = Unit
        override suspend fun printStartOptions() = PrintStartOptions()
        override suspend fun savePrintStartOptions(options: PrintStartOptions) = Unit
    }

    private class FakeImports(private val result: Result<String>) : ModelImportRepository {
        override suspend fun import(uri: String) = result
    }

    private val samples = object : SampleModelRepository {
        override suspend fun calibrationCubePath() = "/samples/cube.stl"
    }

    private var settings = FakeSettings()

    private fun viewModel(
        status: EngineStatus = EngineStatus.Available(info),
        imports: ModelImportRepository = FakeImports(Result.success("/models/benchy.stl")),
    ) = HomeViewModel(GetEngineStatusUseCase(FixedEngine(status)), FakePresets(), settings, imports, samples)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `reports an unavailable engine`() {
        val vm = viewModel(status = EngineStatus.Unavailable("not bundled"))
        assertEquals(EngineUiState.Unavailable("not bundled"), vm.state.value.engine)
    }

    @Test
    fun `choosing a printer fills in its recommended process and filament`() {
        val vm = viewModel()
        assertEquals(listOf("Creality", "Prusa"), vm.state.value.printerSetup.vendors)

        vm.onIntent(HomeIntent.SelectVendor("Creality"))
        vm.onIntent(HomeIntent.SelectPrinter("Creality P1"))
        assertEquals(PresetSelection("Creality", "Creality P1", "Standard", listOf("PLA")), vm.state.value.printerSetup.selection)

        vm.onIntent(HomeIntent.SelectFilament("PETG"))
        assertEquals(PresetSelection("Creality", "Creality P1", "Standard", listOf("PETG")), settings.selection)
    }

    @Test
    fun `changing vendor clears the previous printer choice`() {
        val vm = viewModel()
        vm.onIntent(HomeIntent.SelectVendor("Creality"))
        vm.onIntent(HomeIntent.SelectPrinter("Creality P1"))

        vm.onIntent(HomeIntent.SelectVendor("Prusa"))
        val setup = vm.state.value.printerSetup
        assertEquals(null, setup.selection)
        assertEquals(listOf("Prusa P1", "Prusa P2"), setup.printers)
    }

    @Test
    fun `restores the saved printer setup`() {
        settings = FakeSettings(PresetSelection("Prusa", "Prusa P2", "Fine", listOf("PETG")))
        val vm = viewModel()
        assertEquals(PresetSelection("Prusa", "Prusa P2", "Fine", listOf("PETG")), vm.state.value.printerSetup.selection)
    }

    @Test
    fun `an imported model opens on the plate`() = runTest {
        val vm = viewModel()
        vm.onIntent(HomeIntent.ImportModel("content://picked/benchy.stl"))
        assertEquals(HomeEvent.OpenModel("/models/benchy.stl"), vm.events.first())
        assertEquals(false, vm.state.value.importing)
    }

    @Test
    fun `a failed import is reported`() {
        val vm = viewModel(imports = FakeImports(Result.failure(IllegalArgumentException("Unsupported file type .zip"))))
        vm.onIntent(HomeIntent.ImportModel("content://picked/archive.zip"))
        assertEquals("Unsupported file type .zip", vm.state.value.importError)
    }

    @Test
    fun `the sample cube opens on the plate`() = runTest {
        val vm = viewModel()
        vm.onIntent(HomeIntent.OpenSample)
        assertEquals(HomeEvent.OpenModel("/samples/cube.stl"), vm.events.first())
    }
}
