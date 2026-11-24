package com.orcamobile.core.model

/**
 * Changed settings ([values], serialized as in profiles) over the presets of
 * [selection] (null for the engine defaults).
 */
data class SettingsEdits(
    val selection: PresetSelection?,
    val values: Map<String, String>,
) {
    /**
     * Whether these changes go over [other]: the same printer and process. The
     * filaments, their number and colours do not matter: adding a filament or
     * recolouring one keeps the changes (as the desktop keeps a changed process
     * while filaments are swapped).
     */
    fun appliesTo(other: PresetSelection?): Boolean =
        if (selection == null || other == null) {
            selection == other
        } else {
            selection.vendor == other.vendor && selection.printer == other.printer && selection.process == other.process
        }

    companion object {
        val NONE = SettingsEdits(null, emptyMap())
    }
}
