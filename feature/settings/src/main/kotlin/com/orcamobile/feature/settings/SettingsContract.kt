package com.orcamobile.feature.settings

import com.orcamobile.core.domain.presets.PresetDifference
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.OptionMode
import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.SettingsCheck
import com.orcamobile.core.model.SettingsFix
import com.orcamobile.core.model.UserPreset

/** Everything the settings screen renders. Produced only by [SettingsViewModel]. */
data class SettingsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** The presets being changed; null when slicing with the engine defaults. */
    val presets: PresetSelection? = null,
    val kind: PresetKind = PresetKind.PROCESS,
    /** Pages of [kind], with the options [mode] shows. */
    val pages: List<PageUi> = emptyList(),
    val pageIndex: Int = 0,
    /** The most detailed level shown, as the desktop app's Simple / Advanced / Expert switch. */
    val mode: OptionMode = OptionMode.EXPERT,
    val query: String = "",
    /** Matches of [query] across every tab, when searching. */
    val results: List<GroupUi> = emptyList(),
    val changedByKind: Map<PresetKind, Int> = emptyMap(),
    /** What is being changed (an object, a part, a height range); null for the presets. */
    val subject: String? = null,
    /** The tabs shown: all three for the presets, Process for an object. */
    val kinds: List<PresetKind> = PresetKind.entries,
    /** A change the engine refused, and why. */
    val problem: String? = null,
    /** What the desktop's rules changed on their own, and why (shown until dismissed). */
    val notices: List<String> = emptyList(),
    /** Conflicts between the settings, each with ways out. */
    val checks: List<SettingsCheck> = emptyList(),
    /** The setting the screen was opened on (its key): its editor opens. */
    val focus: String? = null,
    /** The filaments loaded (their presets), when the Filament tab shows one of several. */
    val filamentSlots: List<String> = emptyList(),
    /** The filament whose settings the Filament tab shows. */
    val filamentSlot: Int = 0,
    /** What can be saved as the user's presets (the presets' settings only). */
    val saveTargets: List<SaveTarget> = emptyList(),
    val userPresets: List<UserPreset> = emptyList(),
    /** Two presets being compared, while the comparison is open. */
    val comparison: ComparisonUi? = null,
    /** The last import or export of presets, until dismissed. */
    val transfer: TransferUi? = null,
    /** Leaving after changing settings: how many are unsaved in all, while the screen asks what to do with them. */
    val leaving: Int? = null,
) {
    val changedCount: Int get() = changedByKind.values.sum()
    val searching: Boolean get() = query.isNotBlank()
    val page: PageUi? get() = pages.getOrNull(pageIndex)
}

data class PageUi(val title: String, val groups: List<GroupUi>)

/** A group of rows; [path] places a search result ("Process › Quality"). */
data class GroupUi(val title: String, val rows: List<SettingRow>, val path: String? = null)

/**
 * One option: [value] is what slicing uses (the preset's value or the user's
 * change), [presetValue] what the presets alone give. Both serialized as in profiles.
 */
data class SettingRow(
    val option: ConfigOption,
    val value: String,
    val presetValue: String,
    /** Greyed out by the desktop's rules, with [reason] saying why. */
    val enabled: Boolean = true,
    val reason: String? = null,
    /** The name the rules give it for these values (brim width as ear radius); null for its own. */
    val label: String? = null,
    /** Which value of a per-filament setting is shown and edited (the filament slot). */
    val slot: Int = 0,
) {
    val changed: Boolean
        get() = if (option.type.isVector) {
            SettingValues.element(option, value, slot) != SettingValues.element(option, presetValue, slot)
        } else {
            value != presetValue
        }
}

/** Two presets of [kind] side by side: the settings they set differently. */
data class ComparisonUi(
    val kind: PresetKind,
    /** The presets of [kind] that can be compared. */
    val choices: List<String>,
    val first: String?,
    val second: String?,
    val loading: Boolean = false,
    val differences: List<PresetDifference> = emptyList(),
    val error: String? = null,
)

/** How importing or exporting presets went. */
sealed interface TransferUi {
    data object Working : TransferUi

    /** [added] presets (their names), and why others were left out. */
    data class Imported(val added: List<String>, val problems: List<String>) : TransferUi

    data class Exported(val count: Int) : TransferUi

    data class Failed(val message: String) : TransferUi
}

/** A kind of preset whose changes can be saved as one of the user's presets. */
data class SaveTarget(
    val kind: PresetKind,
    /** The preset the changes are to; saved under its own name when it is one of the user's. */
    val current: String,
    val currentIsUsers: Boolean,
    val changes: Int,
)

sealed interface SettingsIntent {
    data class SelectKind(val kind: PresetKind) : SettingsIntent

    data class SelectPage(val index: Int) : SettingsIntent

    data class SetMode(val mode: OptionMode) : SettingsIntent

    data class Search(val query: String) : SettingsIntent

    /** [edited] is the editable form (see SettingValues.editable), or SettingValues.UNSET. */
    data class Edit(val key: String, val edited: String) : SettingsIntent

    data class Reset(val key: String) : SettingsIntent

    data object ResetAll : SettingsIntent

    data object DismissProblem : SettingsIntent

    /** Takes one way out of a conflict between settings. */
    data class ApplyFix(val fix: SettingsFix) : SettingsIntent

    data object DismissNotices : SettingsIntent

    /** Shows (and edits) filament [slot]'s settings on the Filament tab. */
    data class SelectFilamentSlot(val slot: Int) : SettingsIntent

    /** Saves the changes to [kind]'s settings as the user's preset [name], and chooses it. */
    data class SavePreset(val kind: PresetKind, val name: String) : SettingsIntent

    data class RenamePreset(val kind: PresetKind, val name: String, val newName: String) : SettingsIntent

    data class DeletePreset(val kind: PresetKind, val name: String) : SettingsIntent

    /** Opens the comparison on presets of [kind], starting from the one chosen. */
    data class OpenComparison(val kind: PresetKind) : SettingsIntent

    /** Compares presets [first] and [second] (either null while not picked). */
    data class Compare(val first: String?, val second: String?) : SettingsIntent

    data object CloseComparison : SettingsIntent

    /** Adds the presets in the document at [uri] (a desktop preset, bundle or project). */
    data class ImportPresets(val uri: String) : SettingsIntent

    /** Writes the user's presets to [uri] as a .zip the desktop imports. */
    data class ExportPresets(val uri: String) : SettingsIntent

    data object DismissTransfer : SettingsIntent

    /** Back: asks first when settings of the presets were changed on this visit. */
    data object Leave : SettingsIntent

    /** The answer to [SettingsUiState.leaving]: keep the changes, or discard them all; null stays. */
    data class ConfirmLeave(val keep: Boolean?) : SettingsIntent
}

sealed interface SettingsEvent {
    data object Left : SettingsEvent
}
