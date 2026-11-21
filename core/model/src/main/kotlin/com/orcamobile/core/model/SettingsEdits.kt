package com.orcamobile.core.model

/**
 * Changed settings ([values], serialized as in profiles) over the presets of
 * [selection] (null for the engine defaults).
 */
data class SettingsEdits(
    val selection: PresetSelection?,
    val values: Map<String, String>,
) {
    fun appliesTo(other: PresetSelection?): Boolean = selection == other

    companion object {
        val NONE = SettingsEdits(null, emptyMap())
    }
}
