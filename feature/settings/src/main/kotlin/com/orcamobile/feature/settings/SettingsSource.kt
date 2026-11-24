package com.orcamobile.feature.settings

import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.project.ObjectEditor
import com.orcamobile.core.domain.project.PlateConfigResolver
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.model.ObjectDetails
import com.orcamobile.core.model.ObjectEdit
import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.RulesScope
import com.orcamobile.core.model.SettingTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject

/**
 * Where the settings screen's changes are kept and what they change: the chosen
 * presets (the desktop's process, filament and printer tabs), or one object, one
 * of its parts or one of its height ranges (the desktop's object, part and layer
 * tabs).
 */
interface SettingsSource {
    /** The presets the values come from; null for the engine defaults. */
    val presets: PresetSelection?

    /** What is being changed, for the screen's subtitle; null for the presets. */
    val subject: String?

    /** The tabs shown. */
    val kinds: List<PresetKind>

    /** Only these settings can be changed here; null for every one. */
    suspend fun allowedKeys(): Set<String>?

    /** What [keys] are when not changed here: what this level inherits. */
    suspend fun inherited(keys: List<String>): Map<String, String>

    /** The changes made here, as they change. */
    val changes: Flow<Map<String, String>>

    /** Where the desktop's rules between settings see these changes made. */
    val rulesScope: RulesScope get() = RulesScope.GLOBAL

    /** Settings under the changes made here, over the presets (for the rules). */
    suspend fun rulesBase(): Map<String, String> = emptyMap()

    suspend fun set(key: String, value: String)

    suspend fun reset(key: String)

    suspend fun resetAll()
}

/** What the settings screen opens on: the presets, or a target in a loaded project's object. */
sealed interface SettingsArgs {
    /** The setting (its key) to open on; null for none. */
    val focus: String? get() = null

    data class Presets(override val focus: String? = null) : SettingsArgs

    data class Object(val project: ProjectId, val objectIndex: Int, val target: SettingTarget) : SettingsArgs
}

/** Makes the [SettingsSource] for [SettingsArgs]. */
class SettingsSourceFactory @Inject constructor(
    private val catalog: ConfigOptionCatalog,
    private val userSettings: UserSettingsRepository,
    private val printSettings: PrintSettingsRepository,
    private val objects: ObjectEditor,
    private val configs: PlateConfigResolver,
) {
    suspend fun create(args: SettingsArgs): SettingsSource {
        val presets = userSettings.presetSelection()
        return when (args) {
            is SettingsArgs.Presets -> PresetSettingsSource(presets, catalog, printSettings)
            is SettingsArgs.Object -> ObjectSettingsSource.load(args, presets, catalog, configs, objects)
        }
    }
}

/** The user's changes to the chosen presets, kept until they choose other presets. */
internal class PresetSettingsSource(
    override val presets: PresetSelection?,
    private val catalog: ConfigOptionCatalog,
    private val printSettings: PrintSettingsRepository,
) : SettingsSource {
    override val subject: String? = null
    override val kinds: List<PresetKind> = PresetKind.entries

    override suspend fun allowedKeys(): Set<String>? = null

    override suspend fun inherited(keys: List<String>) = catalog.values(presets, keys).getOrThrow()

    override val changes: Flow<Map<String, String>> =
        printSettings.edits.map { edits -> if (edits.appliesTo(presets)) edits.values else emptyMap() }

    override suspend fun set(key: String, value: String) = printSettings.set(presets, key, value)

    override suspend fun reset(key: String) = printSettings.reset(presets, key)

    override suspend fun resetAll() = printSettings.resetAll()
}

/**
 * Settings of one object, part or height range, kept in the engine's model so
 * the slice uses them. They inherit the presets with the user's changes, and a
 * part or a height range also inherits its object's own settings.
 */
internal class ObjectSettingsSource private constructor(
    private val args: SettingsArgs.Object,
    override val presets: PresetSelection?,
    private val catalog: ConfigOptionCatalog,
    private val configs: PlateConfigResolver,
    private val objects: ObjectEditor,
    initial: ObjectDetails,
) : SettingsSource {
    private val details = MutableStateFlow(initial)
    private val target = args.target

    override val subject: String = subjectOf(initial)
    override val kinds: List<PresetKind> = listOf(PresetKind.PROCESS)

    override suspend fun allowedKeys(): Set<String> = objects.settingKeys(target.scope).getOrThrow().toSet()

    override suspend fun inherited(keys: List<String>): Map<String, String> {
        val presetValues = catalog.values(presets, keys).getOrThrow()
        val edits = configs.configFor(presets).overrides.filterKeys { it in keys }
        val objectValues = if (target == SettingTarget.OBJECT) emptyMap() else details.value.settings.filterKeys { it in keys }
        return presetValues + edits + objectValues
    }

    override val changes: Flow<Map<String, String>> = details.map { settingsOf(it) }

    override val rulesScope: RulesScope = RulesScope.OBJECT

    override suspend fun rulesBase(): Map<String, String> {
        val edits = configs.configFor(presets).overrides
        val objectValues = if (target == SettingTarget.OBJECT) emptyMap() else details.value.settings
        return edits + objectValues
    }

    override suspend fun set(key: String, value: String) = apply(ObjectEdit.SetSetting(target, key, value))

    override suspend fun reset(key: String) {
        // A height range always keeps its own layer height: back to the inherited one instead.
        if (key == LAYER_HEIGHT && target.range != null) {
            inherited(listOf(LAYER_HEIGHT))[LAYER_HEIGHT]?.let { set(key, it) }
            return
        }
        apply(ObjectEdit.ResetSetting(target, key))
    }

    override suspend fun resetAll() {
        for (key in settingsOf(details.value).keys) reset(key)
    }

    private suspend fun apply(edit: ObjectEdit) {
        details.value = objects.edit(args.project, args.objectIndex, edit).getOrThrow().details
    }

    private fun settingsOf(details: ObjectDetails): Map<String, String> {
        val volume = target.volume
        val range = target.range
        return when {
            volume != null -> details.volumes.getOrNull(volume)?.settings.orEmpty()
            range != null -> details.layerRanges.getOrNull(range)?.settings.orEmpty()
            else -> details.settings
        }.filterKeys { it != EXTRUDER }   // set by the app for every new part, not a change of the user's
    }

    private fun subjectOf(details: ObjectDetails): String {
        val volume = target.volume
        val range = target.range
        return when {
            volume != null -> details.volumes.getOrNull(volume)?.name ?: details.name
            range != null -> details.layerRanges.getOrNull(range)?.let {
                "${details.name} · ${format(it.minZ)}–${format(it.maxZ)} mm"
            } ?: details.name
            else -> details.name
        }
    }

    companion object {
        private const val LAYER_HEIGHT = "layer_height"
        private const val EXTRUDER = "extruder"

        private fun format(value: Double) = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

        suspend fun load(
            args: SettingsArgs.Object,
            presets: PresetSelection?,
            catalog: ConfigOptionCatalog,
            configs: PlateConfigResolver,
            objects: ObjectEditor,
        ): ObjectSettingsSource {
            val details = objects.details(args.project, args.objectIndex).getOrThrow().details
            return ObjectSettingsSource(args, presets, catalog, configs, objects, details)
        }
    }
}
