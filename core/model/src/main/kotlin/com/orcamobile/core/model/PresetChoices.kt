package com.orcamobile.core.model

/**
 * Choosing the printer, process and filament presets to slice with: what can be
 * chosen and what is chosen. The process and filament lists are those
 * compatible with the chosen printer.
 */
data class PresetChoices(
    val loading: Boolean = false,
    val vendors: List<String> = emptyList(),
    val vendor: String? = null,
    val printers: List<String> = emptyList(),
    val printer: String? = null,
    val processes: List<String> = emptyList(),
    val process: String? = null,
    val filaments: List<String> = emptyList(),
    val filament: String? = null,
    val error: String? = null,
) {
    /** The complete selection, or null while any part is missing. */
    val selection: PresetSelection?
        get() {
            val vendor = vendor ?: return null
            val printer = printer ?: return null
            val process = process ?: return null
            val filament = filament ?: return null
            return PresetSelection(vendor, printer, process, listOf(filament))
        }
}
