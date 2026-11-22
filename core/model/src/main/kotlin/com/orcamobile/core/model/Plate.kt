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

/** Three values along X, Y and Z. */
data class Axes(val x: Double, val y: Double, val z: Double) {
    operator fun get(axis: Axis): Double = when (axis) {
        Axis.X -> x
        Axis.Y -> y
        Axis.Z -> z
    }

    fun with(axis: Axis, value: Double): Axes = when (axis) {
        Axis.X -> copy(x = value)
        Axis.Y -> copy(y = value)
        Axis.Z -> copy(z = value)
    }

    fun map(transform: (Double) -> Double) = Axes(transform(x), transform(y), transform(z))
}

enum class Axis { X, Y, Z }

/** An instance's transform, as the desktop's object manipulation panel shows it. */
data class InstanceTransform(
    /** mm: the center of the object's box on the bed. */
    val position: Axes,
    /** Degrees about X, Y and Z. */
    val rotationDegrees: Axes,
    /** Factors along the object's own axes (1 = 100 %). */
    val scale: Axes,
    /** mm: the object's own box, scaled. */
    val size: Axes,
    val mirrored: Set<Axis>,
)

/** A change to one instance's transform. Every change leaves it standing on the bed. */
sealed interface TransformEdit {
    /** [z] applies to volumes inside an object; an object always stands on the bed. */
    data class Position(val x: Double, val y: Double, val z: Double? = null) : TransformEdit

    data class Rotation(val degrees: Axes) : TransformEdit

    /** Factors, each more than 0. */
    data class Scale(val factors: Axes) : TransformEdit

    data class Mirror(val axis: Axis) : TransformEdit

    /** No rotation, 100 %, not mirrored. */
    data object Reset : TransformEdit
}

/** An instance's transform after reading or changing it, with the plate's objects. */
data class TransformUpdate(val transform: InstanceTransform, val objects: List<ModelObjectInfo>)
