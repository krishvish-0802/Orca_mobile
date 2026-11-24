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
    /** Sliced; [warnings] are what the slicer would have the user know. */
    data class Succeeded(
        val gcodePath: String,
        val statistics: SliceStatistics,
        val warnings: List<SliceMessage> = emptyList(),
    ) : SliceResult

    data object Canceled : SliceResult

    /** Not sliced: [message] says why; [details] has it per object, with the warnings that came before. */
    data class Failed(val message: String, val details: List<SliceMessage> = emptyList()) : SliceResult
}

/** Something the slicer said about the print. */
data class SliceMessage(
    val level: Level,
    val text: String,
    /** The object it is about, by name; null for the whole plate. */
    val objectName: String? = null,
    /** The setting it is about (its key), when the slicer names one. */
    val setting: String? = null,
    /** What kind of message it is (SliceMessage codes in native/bridge SliceJob.hpp); null when not one the app knows. */
    val code: String? = null,
) {
    enum class Level { INFO, WARNING, ERROR }
}
