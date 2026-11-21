package com.orcamobile.feature.settings

import androidx.annotation.StringRes
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.PresetSelection

/** Everything the settings screen renders. Produced only by [SettingsViewModel]. */
data class SettingsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** The presets being changed; null when slicing with the engine defaults. */
    val presets: PresetSelection? = null,
    val groups: List<SettingsGroupUi> = emptyList(),
) {
    val changedCount: Int get() = groups.sumOf { group -> group.rows.count { it.changed } }
}

data class SettingsGroupUi(@StringRes val title: Int, val rows: List<SettingRow>)

/**
 * One option: [value] is what slicing uses (the preset's value or the user's
 * change), [presetValue] what the presets alone give. Both serialized as in profiles.
 */
data class SettingRow(
    val option: ConfigOption,
    val value: String,
    val presetValue: String,
) {
    val changed: Boolean get() = value != presetValue
}

sealed interface SettingsIntent {
    /** [edited] is the editable form (see SettingValues.editable). */
    data class Edit(val key: String, val edited: String) : SettingsIntent

    data class Reset(val key: String) : SettingsIntent

    data object ResetAll : SettingsIntent
}
