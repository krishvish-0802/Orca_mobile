package com.orcamobile.core.model

/** How a layer's texture is laid onto the surface ([nativeValue] is the bridge's TextureProjection). */
enum class TextureProjection(val nativeValue: Int) {
    /** Sampled along the three world axes and blended by the surface's direction: right for most shapes. */
    TRIPLANAR(0),

    /** Wrapped around an axis through the painted area: for round, column-like parts. */
    CYLINDRICAL(1),

    /** Wrapped around the painted area's centre: for rounded, ball-like parts. */
    SPHERICAL(2),

    /** The painted area flattened out (cut at sharp edges): least stretch on an arbitrary shape. */
    UNWRAP(3),

    /** The model's own texture coordinates, from the file it came in. */
    MODEL_UV(5),
    ;

    companion object {
        fun of(value: Int): TextureProjection = entries.firstOrNull { it.nativeValue == value } ?: TRIPLANAR
    }
}

/** How a layer combines with the layers below it ([nativeValue] is the bridge's TextureBlend). */
enum class TextureBlend(val nativeValue: Int) {
    ADD(0),
    SUBTRACT(1),
    MULTIPLY(2),
    DIVIDE(3),

    /** Only this layer's relief where it is painted. */
    OVERRIDE(4),
    ;

    companion object {
        fun of(value: Int): TextureBlend = entries.firstOrNull { it.nativeValue == value } ?: ADD
    }
}

/** A texture layer's settings, as the desktop panel edits them. Lengths in mm. */
data class TextureLayerSettings(
    /** How far the brightest texel moves the surface out. */
    val depthMm: Float = 0.4f,
    /** The size of one repeat of the texture. */
    val tileMm: Float = 12.5f,
    val rotationDegrees: Float = 0f,
    val offsetXMm: Float = 0f,
    val offsetYMm: Float = 0f,
    /** Dark raises, light stays. */
    val invert: Boolean = false,
    /** The height that stays put: 0 only raises, 0.5 also cuts in where darker. */
    val midlevel: Float = 0f,
    /** Softens the texture before it is applied, 0..1. */
    val smoothing: Float = 0f,
    /** Fades the relief out toward the edge of the paint, by [edgeFade] (0..1). */
    val fadeEdge: Boolean = false,
    val edgeFade: Float = 0.5f,
    /** Repeats the texture; off, it is laid once. */
    val tiled: Boolean = true,
    /** Every other repeat mirrored, so the joins do not show. */
    val mirrorTiles: Boolean = false,
    val projection: TextureProjection = TextureProjection.TRIPLANAR,
    val blend: TextureBlend = TextureBlend.ADD,
) {
    companion object {
        const val MAX_DEPTH_MM = 5f
        const val MIN_TILE_MM = 0.5f
        const val MAX_TILE_MM = 200f
    }
}

/** One texture on an object, painted where it applies. [slot] names its paint (PaintKind.TEXTURE). */
data class TextureLayer(
    val slot: Int,
    val name: String,
    val path: String,
    val widthPx: Int,
    val heightPx: Int,
    /** The image is in colour (its brightness is the height). */
    val hasColour: Boolean,
    /** Some part of the object is painted with it: a bake would use it. */
    val painted: Boolean,
    val settings: TextureLayerSettings,
)

/** An object's texture layers (bottom first) and the settings that apply to all of them. */
data class TextureStack(
    val layers: List<TextureLayer> = emptyList(),
    /** Smooths the relief after the layers are applied, by [smoothStrength] over [smoothPasses]. */
    val smooth: Boolean = false,
    val smoothStrength: Float = 0.3f,
    val smoothPasses: Int = 2,
    /** The paint's outermost ring is displaced too; off pins it flat. */
    val displaceBorder: Boolean = true,
    /** The object carries texture coordinates of its own. */
    val hasModelUv: Boolean = false,
    /** No room for another layer. */
    val full: Boolean = false,
) {
    val anyPainted: Boolean get() = layers.any { it.painted }

    fun layer(slot: Int): TextureLayer? = layers.firstOrNull { it.slot == slot }

    companion object {
        const val MAX_LAYERS = 8
    }
}

/** A texture shipped with the app (OrcaSlicer's library), at [path]. */
data class LibraryTexture(val name: String, val path: String)

/** What a bake did. */
data class TextureBake(
    val parts: Int,
    val trianglesBefore: Long,
    val trianglesAfter: Long,
    /** The triangle budget took detail back out to fit. */
    val budgetLimited: Boolean,
)
