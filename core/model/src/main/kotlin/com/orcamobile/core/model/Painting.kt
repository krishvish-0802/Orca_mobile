package com.orcamobile.core.model

/** What is painted, as the desktop's painting tools ([nativeValue] is the bridge's PaintKind). */
enum class PaintKind(val nativeValue: Int) {
    SUPPORTS(0),
    SEAM(1),
    FUZZY_SKIN(2),
    COLOR(3),

    /** The area of a painted modifier: a modifier shaped by paint on a part, with settings of its own. */
    PAINTED_MODIFIER(4),
}

/** How a touch paints ([nativeValue] is the bridge's PaintTool). */
enum class PaintTool(val nativeValue: Int) {
    /** A circle on the surface under the finger. */
    CIRCLE(0),

    /** A ball: paints through to every surface inside it. */
    SPHERE(1),

    /** Spreads from the touch over faces that bend less than the fill angle. */
    SMART_FILL(2),

    /** The whole connected surface. */
    BUCKET_FILL(3),
}

/**
 * What a touch paints and how: [state] 0 erases; for supports and seams 1
 * enforces and 2 blocks; for fuzzy skin 1 paints; for colour it is the
 * filament number.
 */
data class PaintBrush(
    val kind: PaintKind = PaintKind.SUPPORTS,
    val tool: PaintTool = PaintTool.CIRCLE,
    val state: Int = ENFORCE,
    val radiusMm: Float = 2f,
    val fillAngleDegrees: Float = 30f,
    /** Paint only overhangs steeper than this; null paints everywhere. */
    val overhangAngleDegrees: Float? = null,
    /**
     * Painting a painted modifier: the one shaped (its volume index), or null
     * for a new one, made by the first stroke on the part it lands on.
     */
    val modifier: Int? = null,
) {
    companion object {
        const val ERASE = 0
        const val ENFORCE = 1
        const val BLOCK = 2
        const val MIN_RADIUS_MM = 0.5f
        const val MAX_RADIUS_MM = 20f
    }
}
