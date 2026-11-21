package com.orcamobile.core.model

/** Identifies a G-code file read into the engine for previewing. */
@JvmInline
value class PreviewId(val value: String)

/** What the preview shows about a G-code file. */
data class GCodePreviewSummary(
    val id: PreviewId,
    val gcodePath: String,
    /** Top of each layer, mm. */
    val layerZs: List<Float>,
    val printTimeSeconds: Double,
    val moveCount: Long,
    /** "#RRGGBB" per extruder. */
    val filamentColors: List<String>,
) {
    val layerCount: Int get() = layerZs.size
}

/**
 * What colors the toolpaths, as in OrcaSlicer's preview legend. [nativeValue]
 * is shared with the viewport (ToolpathColoring in native/viewport).
 */
enum class ToolpathColoring(val nativeValue: Int) {
    FeatureType(0),
    Height(1),
    Width(2),
    Speed(3),
    FanSpeed(4),
    Temperature(5),
    VolumetricFlowRate(6),
    LayerTime(7),
    Filament(8),
}
