package com.orcamobile.core.model

/**
 * The OrcaSlicer system presets a slice uses, by name, e.g. vendor "Creality",
 * printer "Creality K1 Max (0.4 nozzle)".
 */
data class PresetSelection(
    val vendor: String,
    val printer: String,
    val process: String,
    val filaments: List<String>,
)

/** Processes and filaments that suit one printer, with the defaults its profile recommends. */
data class CompatiblePresets(
    val processes: List<String>,
    val filaments: List<String>,
    val defaultProcess: String?,
    val defaultFilament: String?,
)
