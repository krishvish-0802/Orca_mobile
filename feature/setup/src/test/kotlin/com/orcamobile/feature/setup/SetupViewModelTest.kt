package com.orcamobile.feature.setup

import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.presets.PresetPicker
import com.orcamobile.core.domain.presets.PresetSwitcher
import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.domain.printer.PrintHostClientFactory
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.domain.usecase.TestPrintHostUseCase
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.CompatiblePresets
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.PresetOptionKeys
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostInfo
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.SettingsEdits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    private val printer = "Elegoo Centauri Carbon 0.4 nozzle"
    private val filaments = listOf("Elegoo PLA", "Generic PETG", "Polymaker PolyTerra PLA", "eSun PLA+")

    private val catalog = object : PresetCatalog {
        override suspend fun vendors() = Result.success(listOf("Creality", "Elegoo"))
        override suspend fun printers(vendor: String) = Result.success(listOf(printer))
        override suspend fun compatiblePresets(vendor: String, printer: String) =
            Result.success(CompatiblePresets(listOf("0.20mm Standard"), filaments, "0.20mm Standard", "Elegoo PLA"))
        override suspend fun bedShape(vendor: String, printer: String) = Result.success(BedShape(emptyList(), 250.0))
    }

    private class Settings : UserSettingsRepository {
        var selection: PresetSelection? = null
        var host: PrintHostConfig? = null
        var done = false
        val shown = mutableMapOf<String, Set<String>>()
        override suspend fun presetSelection() = selection
        override suspend fun savePresetSelection(selection: PresetSelection) {
            this.selection = selection
        }
        override suspend fun printHost() = host
        override suspend fun savePrintHost(host: PrintHostConfig) {
            this.host = host
        }
        override suspend fun printStartOptions() = PrintStartOptions()
        override suspend fun savePrintStartOptions(options: PrintStartOptions) = Unit
        override suspend fun setupDone() = done
        override suspend fun saveSetupDone() {
            done = true
        }
        override suspend fun shownFilaments(printer: String) = shown[printer].orEmpty()
        override suspend fun saveShownFilaments(printer: String, names: Set<String>) {
            shown[printer] = names
        }
    }

    private val printSettings = object : PrintSettingsRepository {
        override val edits = MutableStateFlow(SettingsEdits.NONE)
        override suspend fun editsFor(selection: PresetSelection?) = emptyMap<String, String>()
        override suspend fun set(selection: PresetSelection?, key: String, value: String) = Unit
        override suspend fun reset(selection: PresetSelection?, key: String) = Unit
        override suspend fun resetAll() = Unit
    }

    private val options = object : ConfigOptionCatalog {
        override suspend fun describe(keys: List<String>) = Result.success(emptyList<ConfigOption>())
        override suspend fun values(selection: PresetSelection?, keys: List<String>) = Result.success(emptyMap<String, String>())
        override suspend fun presetOptionKeys() = Result.success(PresetOptionKeys(emptyList(), emptyList(), emptyList()))
    }

    /** A printer that answers. */
    private val hosts = object : PrintHostClientFactory {
        override fun create(config: PrintHostConfig) = object : PrintHostClient {
            override suspend fun testConnection() = Result.success(PrintHostInfo("Centauri Carbon ready"))
            override suspend fun upload(
                localPath: String,
                remoteName: String,
                startPrint: Boolean,
                options: PrintStartOptions,
                onProgress: (sent: Long, total: Long) -> Unit,
            ) = Result.success(Unit)
        }
    }

    private val settings = Settings()
    private val picker = PresetPicker(catalog, settings)

    private fun viewModel() = SetupViewModel(picker, PresetSwitcher(picker, printSettings, options), catalog, settings, TestPrintHostUseCase(hosts))

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the printer, its filaments and the connection are set up`() = runTest {
        val vm = viewModel()
        assertEquals(false, vm.state.value.canGoOn)
        vm.onIntent(SetupIntent.SelectVendor("Elegoo"))
        vm.onIntent(SetupIntent.SelectPrinter(printer))
        vm.onIntent(SetupIntent.Next)

        // The printer's own, its maker's and the generic ones to start with.
        assertEquals(SetupStep.FILAMENTS, vm.state.value.step)
        assertEquals(filaments, vm.state.value.filaments)
        assertEquals(setOf("Elegoo PLA", "Generic PETG"), vm.state.value.shown)
        vm.onIntent(SetupIntent.ToggleFilament("Polymaker PolyTerra PLA"))
        vm.onIntent(SetupIntent.Next)

        assertEquals(SetupStep.CONNECTION, vm.state.value.step)
        assertEquals(PrintHostType.ELEGOO_LINK, vm.state.value.host.type)
        vm.onIntent(SetupIntent.SetHostAddress("192.168.1.40"))
        vm.onIntent(SetupIntent.TestHost)
        assertIs<HostTest.Passed>(vm.state.value.host.test)
        vm.onIntent(SetupIntent.Next)

        assertEquals(SetupEvent.Finished, vm.events.first())
        assertTrue(settings.done)
        assertEquals(PrintHostConfig(PrintHostType.ELEGOO_LINK, "192.168.1.40"), settings.host)
        assertEquals(setOf("Elegoo PLA", "Generic PETG", "Polymaker PolyTerra PLA"), settings.shown[printer])
        // The lists now show only those.
        assertEquals(listOf("Elegoo PLA", "Generic PETG", "Polymaker PolyTerra PLA"), picker.choices.value.filaments)
    }

    @Test
    fun `skipping ends the setup for good`() = runTest {
        val vm = viewModel()
        vm.onIntent(SetupIntent.Skip)
        assertEquals(SetupEvent.Finished, vm.events.first())
        assertTrue(settings.done)
    }
}
