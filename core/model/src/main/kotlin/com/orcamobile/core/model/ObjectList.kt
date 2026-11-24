package com.orcamobile.core.model

/**
 * What a volume of an object is, as OrcaSlicer's ModelVolumeType ([nativeValue]
 * is its value). Only parts print; the others shape or steer the print.
 */
enum class VolumeKind(val nativeValue: Int) {
    PART(0),
    NEGATIVE_VOLUME(1),
    MODIFIER(2),
    SUPPORT_BLOCKER(3),
    SUPPORT_ENFORCER(4),
    SEAM_CENTER(5),
    SEAM_LEFT(6),
    SEAM_RIGHT(7),
    SEAM_ENFORCED(8),
    SEAM_BLOCKED(9),
    SEAM_NEUTRAL(10),
    PAINTED_MODIFIER(11),
    ;

    /** Which settings a volume of this kind takes: parts and modifiers carry region settings. */
    val takesSettings: Boolean
        get() = this == PART || this == MODIFIER || this == PAINTED_MODIFIER

    companion object {
        fun of(nativeValue: Int): VolumeKind = entries.firstOrNull { it.nativeValue == nativeValue } ?: PART

        /** The kinds the "Add" menu offers, as the desktop's object list does. */
        val addable = listOf(PART, NEGATIVE_VOLUME, MODIFIER, SUPPORT_ENFORCER, SUPPORT_BLOCKER)
    }
}

/** The desktop's generic shapes for new volumes, in the order of its menu. */
enum class PrimitiveShape(val nativeValue: Int) { CUBE(0), CYLINDER(1), SPHERE(2), SLAB(3), CONE(4), TORUS(5), DISC(6) }

data class VolumeInfo(
    val name: String,
    val kind: VolumeKind,
    val triangles: Long,
    /** The volume's own settings, by key (serialized as in preset files). */
    val settings: Map<String, String>,
    /** Its placement in the object. */
    val transform: InstanceTransform,
    /** A text or an SVG, which the Text tool edits. */
    val embossed: Boolean = false,
    /** A painted modifier: how far it reaches into its part from the paint, mm; 0 across the part. */
    val paintedDepthMm: Double = 0.0,
)

/** A height range of an object with settings of its own, as the desktop's height range modifier. */
data class LayerRange(val minZ: Double, val maxZ: Double, val settings: Map<String, String>)

/** One object as the desktop's object list shows it. */
data class ObjectDetails(
    val name: String,
    val settings: Map<String, String>,
    val volumes: List<VolumeInfo>,
    val layerRanges: List<LayerRange>,
) {
    val partCount: Int get() = volumes.count { it.kind == VolumeKind.PART }
}

/** An object after reading or changing it, with the plate's objects. */
data class ObjectUpdate(val details: ObjectDetails, val objects: List<ModelObjectInfo>)

/** Where a per-object setting lives: the object itself, one of its volumes, or one of its height ranges. */
data class SettingTarget(val volume: Int? = null, val range: Int? = null) {
    val scope: SettingScope
        get() = when {
            volume != null -> SettingScope.VOLUME
            range != null -> SettingScope.LAYER_RANGE
            else -> SettingScope.OBJECT
        }

    companion object {
        val OBJECT = SettingTarget()
    }
}

/** Which settings a target takes, as the desktop's object, part and layer settings tabs. */
enum class SettingScope(val nativeValue: Int) { OBJECT(0), VOLUME(1), LAYER_RANGE(2) }

/** A change to an object's composition or its own settings. */
sealed interface ObjectEdit {
    data class AddPrimitive(val kind: VolumeKind, val shape: PrimitiveShape, val sizeMm: Double) : ObjectEdit

    /** Every object in the model file at [path], as one volume. */
    data class AddFile(val kind: VolumeKind, val path: String) : ObjectEdit

    data class RemoveVolume(val volume: Int) : ObjectEdit

    /** A part's separate pieces become parts of their own. */
    data class SplitToParts(val volume: Int) : ObjectEdit

    data class SetKind(val volume: Int, val kind: VolumeKind) : ObjectEdit

    /** Fewer triangles in a part, or in every part when [volume] is null. */
    data class Simplify(val volume: Int?, val settings: SimplifySettings) : ObjectEdit

    /**
     * Combines two volumes; the result takes [source]'s place. A union uses up
     * [tool]; a difference or intersection keeps it unless [deleteTool].
     */
    data class MeshBoolean(val source: Int, val tool: Int, val operation: BooleanOperation, val deleteTool: Boolean) : ObjectEdit

    /** Renames a volume, or the object when [volume] is null. */
    data class Rename(val volume: Int?, val name: String) : ObjectEdit

    data class VolumeTransform(val volume: Int, val position: Axes, val rotationDegrees: Axes, val scale: Axes) : ObjectEdit

    data class SetSetting(val target: SettingTarget, val key: String, val value: String) : ObjectEdit

    /** How far painted modifier [volume] reaches into its part, mm; 0 across the part. */
    data class SetPaintedDepth(val volume: Int, val depthMm: Double) : ObjectEdit

    data class ResetSetting(val target: SettingTarget, val key: String) : ObjectEdit

    data class AddRange(val minZ: Double, val maxZ: Double, val layerHeight: String) : ObjectEdit

    data class SetRange(val range: Int, val minZ: Double, val maxZ: Double) : ObjectEdit

    data class RemoveRange(val range: Int) : ObjectEdit
}
