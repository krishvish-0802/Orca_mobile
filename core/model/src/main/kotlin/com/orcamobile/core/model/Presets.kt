package com.orcamobile.core.model

/**
 * The OrcaSlicer system presets a slice uses, by name, e.g. vendor "Creality",
 * printer "Creality K1 Max (0.4 nozzle)".
 */
data class PresetSelection(
    val vendor: String,
    val printer: String,
    val process: String,
    /** One per filament slot: filament 1, 2, ... as objects and painting number them. */
    val filaments: List<String>,
    /** "#RRGGBB" per slot over its preset's colour; empty (or shorter) keeps the preset's. */
    val filamentColours: List<String> = emptyList(),
)

/** Processes and filaments that suit one printer, with the defaults its profile recommends. */
data class CompatiblePresets(
    val processes: List<String>,
    val filaments: List<String>,
    val defaultProcess: String?,
    val defaultFilament: String?,
)
