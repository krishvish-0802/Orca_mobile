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
    /** The filament presets that suit the printer. */
    val filaments: List<String> = emptyList(),
    /** The filaments loaded, in order: filament 1, 2, ... */
    val slots: List<FilamentSlot> = emptyList(),
    /** Each slot's colour as it will print: its own, or its preset's ("#RRGGBB"); may lag a change briefly. */
    val slotColours: List<String> = emptyList(),
    val error: String? = null,
) {
    /** The first filament, which single-filament screens show. */
    val filament: String?
        get() = slots.firstOrNull()?.preset

    /** Slot [index]'s colour as it will print, when known. */
    fun colourOf(index: Int): String? = slots.getOrNull(index)?.colour ?: slotColours.getOrNull(index)

    /** The complete selection, or null while any part is missing. */
    val selection: PresetSelection?
        get() {
            val vendor = vendor ?: return null
            val printer = printer ?: return null
            val process = process ?: return null
            if (slots.isEmpty()) return null
            val colours = slots.map { it.colour.orEmpty() }
            return PresetSelection(
                vendor, printer, process, slots.map { it.preset },
                if (colours.all { it.isEmpty() }) emptyList() else colours,
            )
        }

    companion object {
        /** As many filaments as the desktop's AMS-sized lists offer. */
        const val MAX_FILAMENTS = 16
    }
}

/** One loaded filament: its preset, and its own colour over the preset's when set. */
data class FilamentSlot(val preset: String, val colour: String? = null)
