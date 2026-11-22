package com.orcamobile.core.model

/**
 * The config the plate tools work with: the presets and setting overrides the
 * slice will use, so arranging and orienting see the same bed, brims and
 * supports that will print.
 */
data class PlateConfig(
    val presets: PresetSelection?,
    val overrides: Map<String, String>,
)

/** How objects are packed, as in the desktop's arrange settings. */
data class ArrangeOptions(
    /** Gap between objects in mm; 0 lets each object's brim and supports decide. */
    val spacingMm: Double = 0.0,
    /** Objects may be turned about the vertical axis to pack tighter. */
    val allowRotation: Boolean = false,
    /** Long objects are laid along the Y axis. */
    val alignToYAxis: Boolean = false,
) {
    companion object {
        /** The largest gap the spacing control offers, in mm. */
        const val MAX_SPACING_MM = 50.0
    }
}

/** The plate after a plate tool changed it. */
data class PlateUpdate(
    val objects: List<ModelObjectInfo>,
    /** Instances arranged on the bed, or copies added to it. */
    val placed: Int = 0,
    /** Instances or asked-for copies that did not fit and lie beside the bed. */
    val notPlaced: Int = 0,
)
