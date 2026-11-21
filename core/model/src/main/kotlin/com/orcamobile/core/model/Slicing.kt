package com.orcamobile.core.model

/** What a slice reads its objects from. */
sealed interface SliceSource {
    /** A model file (STL/3MF/OBJ/STEP), centered on the bed for the slice. */
    data class File(val path: String) : SliceSource

    /** A loaded project, sliced as placed. */
    data class Project(val id: ProjectId) : SliceSource
}

/**
 * One slicing job.
 *
 * @property source the model file or loaded project to slice.
 * @property outputGcodePath where the engine writes G-code.
 * @property presets system presets to slice with; null slices with the engine
 *   defaults, which describe no real printer.
 * @property configOverrides OrcaSlicer config keys and serialized values applied last,
 *   over the presets or the defaults, e.g. `"layer_height" to "0.2"`.
 */
data class SliceRequest(
    val source: SliceSource,
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
