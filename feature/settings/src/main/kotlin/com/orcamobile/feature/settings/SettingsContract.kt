package com.orcamobile.feature.settings

import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.OptionMode
import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.SettingsCheck
import com.orcamobile.core.model.SettingsFix

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
) {
    val changed: Boolean get() = value != presetValue
}

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
}
