package com.orcamobile.feature.settings

import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.OptionType
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

    private val catalog = object : ConfigOptionCatalog {
        override suspend fun describe(keys: List<String>) = Result.success(
            listOf(
                ConfigOption("layer_height", OptionType.FLOAT, "Layer height", "Layer height", "Quality", "", "mm", "0.2", min = 0.0),
                ConfigOption("sparse_infill_density", OptionType.PERCENT, "Sparse infill density", "", "Strength", "", "%", "20%", 0.0, 100.0),
                ConfigOption("nozzle_temperature", OptionType.INTS, "Nozzle", "Nozzle temperature", "", "", "°C", "200", 0.0, 500.0),
            ).filter { it.key in keys },
        )

        override suspend fun values(selection: PresetSelection?, keys: List<String>) = Result.success(
            mapOf("layer_height" to "0.2", "sparse_infill_density" to "15%", "nozzle_temperature" to "220,220").filterKeys { it in keys },
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

    private fun rows(vm: SettingsViewModel) = vm.state.value.groups.flatMap { it.rows }.associateBy { it.option.key }

    @Test
    fun `shows the presets' values in their groups`() {
        val vm = SettingsViewModel(catalog, userSettings, printSettings)
        val state = vm.state.value
        assertEquals(presets, state.presets)
        assertEquals(0, state.changedCount)
        assertEquals("0.2", rows(vm).getValue("layer_height").value)
        assertEquals(R.string.settings_group_quality, state.groups.first().title)
    }

    @Test
    fun `edits are stored for the chosen presets and can be undone`() {
        val vm = SettingsViewModel(catalog, userSettings, printSettings)

        vm.onIntent(SettingsIntent.Edit("sparse_infill_density", "25"))
        vm.onIntent(SettingsIntent.Edit("nozzle_temperature", "215"))
        assertEquals(mapOf("sparse_infill_density" to "25%", "nozzle_temperature" to "215,220"), printSettings.state.value.values)
        assertEquals(presets, printSettings.state.value.selection)
        assertEquals(2, vm.state.value.changedCount)
        assertEquals("15%", rows(vm).getValue("sparse_infill_density").presetValue)

        vm.onIntent(SettingsIntent.Reset("nozzle_temperature"))
        assertEquals(1, vm.state.value.changedCount)

        // Typing the preset's own value back is no change.
        vm.onIntent(SettingsIntent.Edit("sparse_infill_density", "15"))
        assertEquals(0, vm.state.value.changedCount)
    }

    @Test
    fun `invalid values are not stored`() {
        val vm = SettingsViewModel(catalog, userSettings, printSettings)
        vm.onIntent(SettingsIntent.Edit("sparse_infill_density", "150"))
        vm.onIntent(SettingsIntent.Edit("layer_height", "thin"))
        assertEquals(emptyMap(), printSettings.state.value.values)
    }
}
