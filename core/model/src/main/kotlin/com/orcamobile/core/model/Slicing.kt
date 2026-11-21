package com.orcamobile.core.model

/**
 * One slicing job.
 *
 * @property modelPath absolute path to an STL/3MF/OBJ/STEP file.
 * @property outputGcodePath where the engine writes G-code.
 * @property presets system presets to slice with; null slices with the engine
 *   defaults, which describe no real printer.
 * @property configOverrides OrcaSlicer config keys and serialized values applied last,
 *   over the presets or the defaults, e.g. `"layer_height" to "0.2"`.
 */
data class SliceRequest(
    val modelPath: String,
    val outputGcodePath: String,
    val presets: PresetSelection? = null,
    val configOverrides: Map<String, String> = emptyMap(),
)

data class SliceStatistics(
    val printTimeSeconds: Double,
    val filamentUsedMm: Double,
    val filamentUsedGrams: Double,
    val layerCount: Int,
    val gcodeBytes: Long,
)

/** What a running slice reports. Ends with exactly one [Finished]. */
sealed interface SliceEvent {
    data class Progress(val percent: Int, val message: String) : SliceEvent

    data class Finished(val result: SliceResult) : SliceEvent
}

sealed interface SliceResult {
    data class Succeeded(val gcodePath: String, val statistics: SliceStatistics) : SliceResult

    data object Canceled : SliceResult

    data class Failed(val message: String) : SliceResult
}
