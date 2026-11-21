package com.orcamobile.feature.settings

import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.OptionMode
import com.orcamobile.core.model.OptionType
import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.PresetOptionKeys
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.SettingsEdits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val presets = PresetSelection("Elegoo", "Elegoo Centauri Carbon 0.4 nozzle", "0.20mm Standard @Elegoo CC", listOf("Elegoo PLA"))

    private val options = listOf(
        ConfigOption("layer_height", OptionType.FLOAT, "Layer height", "Layer height", "Quality", "Height of each layer", "mm", "0.2", min = 0.0),
        ConfigOption("sparse_infill_density", OptionType.PERCENT, "Sparse infill density", "", "Strength", "", "%", "20%", 0.0, 100.0),
        ConfigOption("ironing_expansion", OptionType.FLOAT, "Ironing expansion", "", "", "", "mm", "0", mode = OptionMode.EXPERT),
        ConfigOption("nozzle_temperature", OptionType.INTS, "Nozzle", "Nozzle temperature", "", "", "°C", "200", 0.0, 500.0),
        ConfigOption("machine_start_gcode", OptionType.STRING, "Start G-code", "", "", "", "", "", mode = OptionMode.ADVANCED, multiline = true),
    ).associateBy { it.key }

    private val catalog = object : ConfigOptionCatalog {
        override suspend fun describe(keys: List<String>) = Result.success(keys.mapNotNull { options[it] })
        override suspend fun values(selection: PresetSelection?, keys: List<String>) = Result.success(
            mapOf(
                "layer_height" to "0.2", "sparse_infill_density" to "15%", "ironing_expansion" to "0",
                "nozzle_temperature" to "220,220", "machine_start_gcode" to "G28\\nG1 Z5",
            ).filterKeys { it in keys },
        )
        override suspend fun presetOptionKeys() = Result.success(
            PresetOptionKeys(
                process = listOf("layer_height", "sparse_infill_density", "ironing_expansion"),
                filament = listOf("nozzle_temperature"),
                printer = listOf("machine_start_gcode"),
            ),
        )
    }

    private val layout = SettingsLayoutSource {
        SettingsTabs.parse(
            """{"tabs":{
              "process":[{"page":"Quality","groups":[{"group":"Layer height","options":["layer_height"]}]},
                         {"page":"Strength","groups":[{"group":"Sparse infill","options":["sparse_infill_density"]}]}],
              "filament":[{"page":"Filament","groups":[{"group":"Print temperature","options":["nozzle_temperature"]}]}],
              "printer":[{"page":"Machine G-code","groups":[{"group":"Machine start G-code","options":["machine_start_gcode"]}]}]}}""",
        )
    }

    private val userSettings = object : UserSettingsRepository {
        override suspend fun presetSelection() = presets
        override suspend fun savePresetSelection(selection: PresetSelection) = Unit
        override suspend fun printHost(): PrintHostConfig? = null
        override suspend fun savePrintHost(host: PrintHostConfig) = Unit
        override suspend fun printStartOptions() = PrintStartOptions()
        override suspend fun savePrintStartOptions(options: PrintStartOptions) = Unit
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

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): SettingsViewModel {
        val dispatcher = UnconfinedTestDispatcher()
        return SettingsViewModel(catalog, userSettings, printSettings, layout, CoroutineDispatchers(dispatcher, dispatcher, dispatcher))
    }

    private fun SettingsViewModel.rows() = (state.value.pages.flatMap { it.groups } + state.value.results).flatMap { it.rows }.associateBy { it.option.key }

    @Test
    fun `shows the desktop pages, with options the layout misses on an Other page`() {
        val vm = viewModel()
        assertEquals(listOf("Quality", "Strength", "Other"), vm.state.value.pages.map { it.title })

        vm.onIntent(SettingsIntent.SelectPage(2))
        assertEquals(listOf("ironing_expansion"), vm.state.value.page?.groups?.single()?.rows?.map { it.option.key })

        vm.onIntent(SettingsIntent.SelectKind(PresetKind.PRINTER))
        assertEquals(0, vm.state.value.pageIndex)
        assertEquals("G28\\nG1 Z5", vm.rows().getValue("machine_start_gcode").value)
    }

    @Test
    fun `simple mode hides advanced and expert options`() {
        val vm = viewModel()
        vm.onIntent(SettingsIntent.SetMode(OptionMode.SIMPLE))
        assertEquals(listOf("Quality", "Strength"), vm.state.value.pages.map { it.title })
        vm.onIntent(SettingsIntent.SelectKind(PresetKind.PRINTER))
        assertEquals(emptyList(), vm.state.value.pages)
    }

    @Test
    fun `search looks through every tab`() {
        val vm = viewModel()
        vm.onIntent(SettingsIntent.Search("temperature"))
        val results = vm.state.value.results
        assertEquals(listOf("nozzle_temperature"), results.flatMap { it.rows }.map { it.option.key })
        assertEquals("Filament › Filament", results.single().path)

        vm.onIntent(SettingsIntent.Search("height of each"))
        assertEquals(listOf("layer_height"), vm.state.value.results.flatMap { it.rows }.map { it.option.key })
    }

    @Test
    fun `edits are stored for the chosen presets and counted per tab`() {
        val vm = viewModel()
        vm.onIntent(SettingsIntent.Edit("sparse_infill_density", "25"))
        vm.onIntent(SettingsIntent.SelectKind(PresetKind.FILAMENT))
        vm.onIntent(SettingsIntent.Edit("nozzle_temperature", "215"))
        vm.onIntent(SettingsIntent.SelectKind(PresetKind.PRINTER))
        vm.onIntent(SettingsIntent.Edit("machine_start_gcode", "G28\nM84"))

        assertEquals(
            mapOf("sparse_infill_density" to "25%", "nozzle_temperature" to "215,220", "machine_start_gcode" to "G28\\nM84"),
            printSettings.state.value.values,
        )
        assertEquals(mapOf(PresetKind.PROCESS to 1, PresetKind.FILAMENT to 1, PresetKind.PRINTER to 1), vm.state.value.changedByKind)

        // Typing the preset's own value back is no change.
        vm.onIntent(SettingsIntent.SelectKind(PresetKind.PROCESS))
        vm.onIntent(SettingsIntent.Edit("sparse_infill_density", "15"))
        assertEquals(0, vm.state.value.changedByKind[PresetKind.PROCESS])
    }

    @Test
    fun `invalid values are not stored`() {
        val vm = viewModel()
        vm.onIntent(SettingsIntent.Edit("sparse_infill_density", "150"))
        vm.onIntent(SettingsIntent.Edit("layer_height", "thin"))
        assertEquals(emptyMap(), printSettings.state.value.values)
    }
}
