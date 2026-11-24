package com.orcamobile.feature.home

import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.domain.model.ModelImportRepository
import com.orcamobile.core.domain.model.SampleModelRepository
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.presets.PresetPicker
import com.orcamobile.core.domain.presets.PresetSwitcher
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.domain.usecase.GetEngineStatusUseCase
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.CompatiblePresets
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.EngineInfo
import com.orcamobile.core.model.EngineStatus
import com.orcamobile.core.model.PresetOptionKeys
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.SettingsEdits
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

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
        var done = true
        override suspend fun setupDone() = done
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
    ): HomeViewModel {
        val picker = PresetPicker(FakePresets(), settings)
        return HomeViewModel(GetEngineStatusUseCase(FixedEngine(status)), picker, imports, samples, updates, PresetSwitcher(picker, printSettings, optionCatalog), settings)
    }

    /** No changed settings: preset changes never ask. */
    private val printSettings = object : PrintSettingsRepository {
        override val edits: Flow<SettingsEdits> = MutableStateFlow(SettingsEdits.NONE)
        override suspend fun editsFor(selection: PresetSelection?) = emptyMap<String, String>()
        override suspend fun set(selection: PresetSelection?, key: String, value: String) = Unit
        override suspend fun reset(selection: PresetSelection?, key: String) = Unit
        override suspend fun resetAll() = Unit
    }

    /** Which settings each kind of preset holds; the presets give the defaults. */
    private val optionCatalog = object : ConfigOptionCatalog {
        override suspend fun describe(keys: List<String>) = Result.success(emptyList<ConfigOption>())
        override suspend fun values(selection: PresetSelection?, keys: List<String>) =
            Result.success(mapOf("layer_height" to "0.2").filterKeys { it in keys })
        override suspend fun presetOptionKeys() = Result.success(PresetOptionKeys(listOf("layer_height"), emptyList(), emptyList()))
    }


    /** GitHub with build 200 waiting, unless [latest] says otherwise. */
    private class FakeUpdates : com.orcamobile.core.domain.update.AppUpdates {
        var latest: com.orcamobile.core.model.AppUpdate? =
            com.orcamobile.core.model.AppUpdate(200, "abc", listOf("Painting"), "https://example.com/app.apk")
        var checks = 0
        var installs = mutableListOf<String>()
        var permissionGranted = true

        override suspend fun check(): Result<com.orcamobile.core.model.AppUpdate?> {
            checks++
            return Result.success(latest)
        }

        override suspend fun download(update: com.orcamobile.core.model.AppUpdate, onProgress: (Long, Long) -> Unit): Result<String> {
            onProgress(50, 100)
            onProgress(100, 100)
            return Result.success("/cache/updates/orca-mobile-${update.versionCode}.apk")
        }

        override fun install(apkPath: String): Result<com.orcamobile.core.model.InstallStart> {
            installs += apkPath
            return Result.success(
                if (permissionGranted) com.orcamobile.core.model.InstallStart.Started else com.orcamobile.core.model.InstallStart.NeedsPermission,
            )
        }
    }

    private val updates = FakeUpdates()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the first launch opens the setup`() = kotlinx.coroutines.test.runTest {
        settings.done = false
        val vm = viewModel()
        assertEquals(HomeEvent.OpenSetup, vm.events.first())
    }

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

    @Test
    fun `a newer build is offered, downloaded and handed to the installer`() {
        val vm = viewModel()
        vm.onIntent(HomeIntent.CheckForUpdate)
        assertEquals(200, (vm.state.value.update as UpdateUiState.Available).update.versionCode)

        vm.onIntent(HomeIntent.DownloadUpdate)
        val ready = vm.state.value.update as UpdateUiState.Ready
        assertEquals("/cache/updates/orca-mobile-200.apk", ready.apkPath)
        assertEquals(listOf("/cache/updates/orca-mobile-200.apk"), updates.installs)
    }

    @Test
    fun `without permission to install the card says so and installs on the next tap`() {
        updates.permissionGranted = false
        val vm = viewModel()
        vm.onIntent(HomeIntent.CheckForUpdate)
        vm.onIntent(HomeIntent.DownloadUpdate)
        assertEquals(true, (vm.state.value.update as UpdateUiState.Ready).needsPermission)
        updates.permissionGranted = true
        vm.onIntent(HomeIntent.InstallUpdate)
        assertEquals(false, (vm.state.value.update as UpdateUiState.Ready).needsPermission)
    }

    @Test
    fun `checks are spaced out and a dismissed build stays dismissed`() {
        val vm = viewModel()
        vm.onIntent(HomeIntent.CheckForUpdate)
        vm.onIntent(HomeIntent.DismissUpdate)
        assertEquals(UpdateUiState.None, vm.state.value.update)
        vm.onIntent(HomeIntent.CheckForUpdate)
        assertEquals(1, updates.checks)
    }

    @Test
    fun `up to date shows nothing`() {
        updates.latest = null
        val vm = viewModel()
        vm.onIntent(HomeIntent.CheckForUpdate)
        assertEquals(UpdateUiState.None, vm.state.value.update)
    }
}
