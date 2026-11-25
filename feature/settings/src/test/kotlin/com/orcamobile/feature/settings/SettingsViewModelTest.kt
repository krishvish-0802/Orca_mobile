package com.orcamobile.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.presets.ComparePresetsUseCase
import com.orcamobile.core.domain.presets.InMemoryUserPresets
import com.orcamobile.core.domain.presets.PresetDocuments
import com.orcamobile.core.domain.presets.PresetFileContents
import com.orcamobile.core.domain.presets.PresetFileFormat
import com.orcamobile.core.domain.presets.PresetPicker
import com.orcamobile.core.domain.presets.PresetSwitcher
import com.orcamobile.core.domain.presets.TransferPresetsUseCase
import com.orcamobile.core.domain.project.ObjectEditor
import com.orcamobile.core.domain.project.PlateConfigResolver
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.model.Axes
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.CompatiblePresets
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.InstanceTransform
import com.orcamobile.core.model.LayerRange
import com.orcamobile.core.model.ObjectDetails
import com.orcamobile.core.model.ObjectEdit
import com.orcamobile.core.model.ObjectUpdate
import com.orcamobile.core.model.OptionMode
import com.orcamobile.core.model.OptionRuleState
import com.orcamobile.core.model.OptionType
import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.PresetOptionKeys
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.RulesScope
import com.orcamobile.core.model.SettingScope
import com.orcamobile.core.model.SettingTarget
import com.orcamobile.core.model.SettingsCheck
import com.orcamobile.core.model.SettingsEdits
import com.orcamobile.core.model.SettingsFix
import com.orcamobile.core.model.SettingsReview
import com.orcamobile.core.model.UserPreset
import com.orcamobile.core.model.VolumeInfo
import com.orcamobile.core.model.VolumeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

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

    /** The desktop's rules, as the test needs them: settings over the presets in, review out. */
    private var rules: (Map<String, String>, RulesScope) -> SettingsReview = { _, _ -> SettingsReview.NONE }

    private val catalog = object : ConfigOptionCatalog {
        override suspend fun review(selection: PresetSelection?, settings: Map<String, String>, scope: RulesScope, filament: Int) =
            Result.success(rules(settings, scope))

        override suspend fun describe(keys: List<String>) = Result.success(keys.mapNotNull { options[it] })
        override suspend fun values(selection: PresetSelection?, keys: List<String>) = Result.success(
            mapOf(
                "layer_height" to if (selection?.process == FINE) "0.12" else "0.2", "sparse_infill_density" to "15%", "ironing_expansion" to "0",
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
        var saved: PresetSelection = presets
        override suspend fun presetSelection() = saved
        override suspend fun savePresetSelection(selection: PresetSelection) {
            saved = selection
        }
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

    /** The engine's object list: a cube with a modifier and a height range. */
    private inner class FakeObjects : ObjectEditor {
        val edits = mutableListOf<ObjectEdit>()
        private val transform = InstanceTransform(
            Axes(0.0, 0.0, 0.0), Axes(0.0, 0.0, 0.0), Axes(1.0, 1.0, 1.0), Axes(20.0, 20.0, 20.0), emptySet(),
        )
        var details = ObjectDetails(
            name = "cube",
            settings = mapOf("sparse_infill_density" to "30%"),
            volumes = listOf(
                VolumeInfo("cube", VolumeKind.PART, 12, mapOf("extruder" to "0"), transform),
                VolumeInfo("Generic-Cube", VolumeKind.MODIFIER, 12, mapOf("extruder" to "0"), transform),
            ),
            layerRanges = listOf(LayerRange(0.0, 2.0, mapOf("layer_height" to "0.1"))),
        )

        override suspend fun details(project: ProjectId, objectIndex: Int) = Result.success(ObjectUpdate(details, emptyList()))

        override suspend fun edit(project: ProjectId, objectIndex: Int, edit: ObjectEdit): Result<ObjectUpdate> {
            edits += edit
            if (edit is ObjectEdit.SetSetting && edit.target.volume == 1) {
                val volumes = details.volumes.toMutableList()
                volumes[1] = volumes[1].copy(settings = volumes[1].settings + (edit.key to edit.value))
                details = details.copy(volumes = volumes)
            }
            return Result.success(ObjectUpdate(details, emptyList()))
        }

        override suspend fun settingKeys(scope: SettingScope) = Result.success(
            when (scope) {
                SettingScope.OBJECT -> listOf("sparse_infill_density", "layer_height", "ironing_expansion")
                SettingScope.VOLUME -> listOf("sparse_infill_density", "ironing_expansion")
                SettingScope.LAYER_RANGE -> listOf("layer_height", "sparse_infill_density", "ironing_expansion")
            },
        )
    }

    private val objects = FakeObjects()

    /** The presets the selection names, with the user's listed after the system ones they inherit. */
    private val presetCatalog = object : PresetCatalog {
        var users: List<UserPreset> = emptyList()
        override suspend fun vendors() = Result.success(listOf(presets.vendor))
        override suspend fun printers(vendor: String) = Result.success(listOf(presets.printer))
        override suspend fun compatiblePresets(vendor: String, printer: String) = Result.success(
            CompatiblePresets(
                listOf(presets.process) + users.filter { it.kind == PresetKind.PROCESS }.map { it.name },
                presets.filaments + users.filter { it.kind == PresetKind.FILAMENT }.map { it.name },
                presets.process,
                presets.filaments.first(),
            ),
        )
        override suspend fun bedShape(vendor: String, printer: String) = Result.success(BedShape(emptyList(), 250.0))
        override suspend fun setUserPresets(presets: List<UserPreset>): Result<Unit> {
            users = presets
            return Result.success(Unit)
        }
    }
    /** No preset files here (importing is tested with the domain). */
    private val noFiles = object : PresetFileFormat, PresetDocuments {
        override suspend fun read(vendor: String, path: String) = Result.failure<PresetFileContents>(UnsupportedOperationException())
        override suspend fun write(preset: UserPreset) = Result.success("{}")
        override suspend fun open(uri: String) = Result.success(emptyList<String>())
        override suspend fun saveZip(uri: String, files: Map<String, String>) = Result.success(Unit)
    }
    private val userPresets = InMemoryUserPresets()
    private val picker = PresetPicker(presetCatalog, userSettings, userPresets)

    private fun viewModel(args: Map<String, Any> = emptyMap()): SettingsViewModel {
        val dispatcher = UnconfinedTestDispatcher()
        val sources = SettingsSourceFactory(catalog, userSettings, printSettings, objects, PlateConfigResolver(printSettings))
        return SettingsViewModel(
            SavedStateHandle(args), sources, catalog, layout, CoroutineDispatchers(dispatcher, dispatcher, dispatcher), picker,
            PresetSwitcher(picker, printSettings, catalog),
            ComparePresetsUseCase(picker, catalog),
            TransferPresetsUseCase(picker, noFiles, noFiles),
        )
    }

    private fun objectArgs(volume: Int = -1, range: Int = -1) = mapOf(
        SETTINGS_PROJECT_ARG to "p1",
        SETTINGS_OBJECT_ARG to 0,
        SETTINGS_VOLUME_ARG to volume,
        SETTINGS_RANGE_ARG to range,
    )

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
    fun `the rules hide, grey out, reset with a notice and offer fixes`() {
        val fine = SettingsFix("Use 0.28 mm", mapOf("layer_height" to "0.28"))
        rules = { settings, _ ->
            SettingsReview(
                options = mapOf(
                    "ironing_expansion" to OptionRuleState(visible = false, reason = "Enable Z contouring to use this setting."),
                    "sparse_infill_density" to OptionRuleState(enabled = false, reason = "Because spiral vase."),
                ),
                // A zero layer height goes back to 0.2 mm, as the desktop does.
                changes = if (settings["layer_height"] == "0") mapOf("layer_height" to "0.2") else emptyMap(),
                notices = if (settings["layer_height"] == "0") listOf("Layer height can't be zero.") else emptyList(),
                checks = listOf(SettingsCheck("coarse", "Thicker layers print faster.", listOf(fine))),
            )
        }
        val vm = viewModel()
        assertEquals(listOf("Quality", "Strength"), vm.state.value.pages.map { it.title })
        vm.onIntent(SettingsIntent.SelectPage(1))
        val density = vm.rows().getValue("sparse_infill_density")
        assertEquals(false, density.enabled)
        assertEquals("Because spiral vase.", density.reason)

        vm.onIntent(SettingsIntent.SelectPage(0))
        vm.onIntent(SettingsIntent.Edit("layer_height", "0"))
        assertEquals("0.2", vm.rows().getValue("layer_height").value)
        assertEquals(emptyMap(), printSettings.state.value.values)
        assertEquals(listOf("Layer height can't be zero."), vm.state.value.notices)
        vm.onIntent(SettingsIntent.DismissNotices)
        assertEquals(emptyList(), vm.state.value.notices)

        assertEquals("coarse", vm.state.value.checks.single().id)
        vm.onIntent(SettingsIntent.ApplyFix(fine))
        assertEquals("0.28", vm.rows().getValue("layer_height").value)
    }

    @Test
    fun `opened on a setting, it shows that setting's page and editor`() {
        val vm = viewModel(mapOf(SETTINGS_FOCUS_ARG to "nozzle_temperature"))
        assertEquals(PresetKind.FILAMENT, vm.state.value.kind)
        assertEquals("nozzle_temperature", vm.state.value.focus)
        assertTrue(vm.rows().containsKey("nozzle_temperature"))
    }

    @Test
    fun `changes are saved as the user's preset, which is then chosen`() = kotlinx.coroutines.test.runTest {
        val vm = viewModel()
        vm.onIntent(SettingsIntent.Edit("layer_height", "0.12"))
        vm.onIntent(SettingsIntent.Edit("nozzle_temperature", "215"))
        assertEquals(listOf(PresetKind.PROCESS, PresetKind.FILAMENT), vm.state.value.saveTargets.map { it.kind })

        vm.onIntent(SettingsIntent.SavePreset(PresetKind.PROCESS, "Fine PLA"))
        assertEquals(listOf(UserPreset(PresetKind.PROCESS, "Fine PLA", presets.process, mapOf("layer_height" to "0.12"))), userPresets.all())
        assertEquals("Fine PLA", picker.choices.value.process)
        // The filament change stays, over the new presets.
        assertEquals(mapOf("nozzle_temperature" to "215,220"), printSettings.state.value.values)
        assertEquals("Fine PLA", printSettings.state.value.selection?.process)
        assertEquals(listOf(PresetKind.FILAMENT), vm.state.value.saveTargets.map { it.kind })

        vm.onIntent(SettingsIntent.DeletePreset(PresetKind.PROCESS, "Fine PLA"))
        assertEquals(presets.process, picker.choices.value.process)
        assertEquals(emptyList(), userPresets.all())
    }

    @Test
    fun `leaving asks about this visit's changes, and discarding takes them back`() = kotlinx.coroutines.test.runTest {
        kotlinx.coroutines.runBlocking { printSettings.set(presets, "sparse_infill_density", "40%") }
        val vm = viewModel()
        // Nothing changed on this visit: back just leaves.
        vm.onIntent(SettingsIntent.Leave)
        assertEquals(SettingsEvent.Left, vm.events.first())

        vm.onIntent(SettingsIntent.Edit("layer_height", "0.12"))
        vm.onIntent(SettingsIntent.Leave)
        assertEquals(1, vm.state.value.leaving)
        vm.onIntent(SettingsIntent.ConfirmLeave(null))
        assertNull(vm.state.value.leaving)

        vm.onIntent(SettingsIntent.Leave)
        vm.onIntent(SettingsIntent.ConfirmLeave(keep = false))
        assertEquals(SettingsEvent.Left, vm.events.first())
        // The change from before this visit stays.
        assertEquals(mapOf("sparse_infill_density" to "40%"), printSettings.state.value.values)
    }

    @Test
    fun `two processes compare by the settings they set differently`() {
        val vm = viewModel()
        vm.onIntent(SettingsIntent.OpenComparison(PresetKind.PROCESS))
        assertEquals(presets.process, vm.state.value.comparison?.first)

        vm.onIntent(SettingsIntent.Compare(presets.process, FINE))
        val differences = vm.state.value.comparison?.differences.orEmpty()
        assertEquals(listOf(Triple("layer_height", "0.2", "0.12")), differences.map { Triple(it.option.key, it.first, it.second) })

        vm.onIntent(SettingsIntent.Compare(presets.process, presets.process))
        assertEquals(emptyList(), vm.state.value.comparison?.differences)
        vm.onIntent(SettingsIntent.CloseComparison)
        assertNull(vm.state.value.comparison)
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

    @Test
    fun `an object's settings show the process tab only, with the keys it takes`() {
        val vm = viewModel(objectArgs())
        val state = vm.state.value
        assertEquals("cube", state.subject)
        assertEquals(listOf(PresetKind.PROCESS), state.kinds)
        val rows = vm.rows()
        assertEquals(setOf("layer_height", "sparse_infill_density", "ironing_expansion"), rows.keys)
        assertEquals("30%", rows.getValue("sparse_infill_density").value)
        assertEquals("15%", rows.getValue("sparse_infill_density").presetValue)
    }

    @Test
    fun `a part inherits its object's settings and changes go to the part`() {
        val vm = viewModel(objectArgs(volume = 1))
        assertEquals("Generic-Cube", vm.state.value.subject)
        val rows = vm.rows()
        assertTrue("layer_height" !in rows)
        assertEquals("30%", rows.getValue("sparse_infill_density").presetValue)
        assertTrue(!rows.getValue("sparse_infill_density").changed)

        vm.onIntent(SettingsIntent.Edit("sparse_infill_density", "60"))
        assertEquals(ObjectEdit.SetSetting(SettingTarget(volume = 1), "sparse_infill_density", "60%"), objects.edits.single())
        assertEquals("60%", vm.rows().getValue("sparse_infill_density").value)
        assertEquals(0, printSettings.state.value.values.size)
    }

    @Test
    fun `a height range keeps a layer height when reset`() {
        val vm = viewModel(objectArgs(range = 0))
        assertEquals("cube · 0–2 mm", vm.state.value.subject)
        assertEquals("0.1", vm.rows().getValue("layer_height").value)
        vm.onIntent(SettingsIntent.Reset("layer_height"))
        assertEquals(ObjectEdit.SetSetting(SettingTarget(range = 0), "layer_height", "0.2"), objects.edits.single())
    }
}

private const val FINE = "0.12mm Fine"
