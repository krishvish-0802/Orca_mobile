package com.orcamobile.core.model

// The desktop's per-object tools beyond the mesh tools: variable layer height,
// brim ears and emboss (text and SVG).

/**
 * An object's layer heights: [profile] is (z, layer height) pairs from the
 * bottom up, mm above the object's base; [layers] the bottom and top of each
 * layer it makes. [custom] when the object has its own profile.
 */
data class LayerProfile(
    val profile: List<Double>,
    val layers: List<Double>,
    val layerHeight: Double,
    val minLayerHeight: Double,
    val maxLayerHeight: Double,
    val objectHeight: Double,
    val custom: Boolean,
) {
    val layerCount: Int get() = layers.size / 2
}

/** What a brush stroke does to the layers ([nativeValue] is libslic3r's LayerHeightEditActionType). */
enum class LayerBrush(val nativeValue: Int) { INCREASE(0), DECREASE(1), REDUCE(2), SMOOTH(3) }

sealed interface LayerProfileAction {
    data object Get : LayerProfileAction

    /** Thinner layers where the surface slopes; [quality] 0 (fast) .. 1 (fine). */
    data class Adaptive(val quality: Double) : LayerProfileAction

    data class Smooth(val radius: Int, val keepMin: Boolean) : LayerProfileAction

    /** One brush step at [zMm] over a band [bandMm] high, by [deltaMm]. */
    data class Brush(val zMm: Double, val deltaMm: Double, val bandMm: Double, val brush: LayerBrush) : LayerProfileAction

    data object Reset : LayerProfileAction
}

/** A brim ear on the bed, world mm. */
data class BrimEar(val x: Double, val y: Double, val radius: Double)

/**
 * What to emboss: [text] in the font at [fontPath], or the SVG at [svgPath].
 * Text sizes are mm except the gaps, which are font points.
 */
data class EmbossSource(
    val text: String = "",
    val fontPath: String = "",
    val letterHeightMm: Double = 10.0,
    val charGap: Double = 0.0,
    val lineGap: Double = 0.0,
    val boldnessMm: Double = 0.0,
    val skew: Double = 0.0,
    val svgPath: String = "",
    /** 0 keeps the SVG's own size. */
    val svgWidthMm: Double = 0.0,
    val depthMm: Double = 2.0,
) {
    val isSvg: Boolean get() = svgPath.isNotEmpty()
}
