package com.orcamobile.core.model

/** Identifies a model loaded in the engine for editing and slicing. */
@JvmInline
value class ProjectId(val value: String)

/** One object of a loaded project. */
data class ModelObjectInfo(
    val name: String,
    val instances: Int,
    val triangles: Long,
    /** Size of the first instance, x/y/z in mm. */
    val sizeMm: List<Double>,
)

data class ProjectSummary(
    val id: ProjectId,
    val sourcePath: String,
    val objects: List<ModelObjectInfo>,
)

/** A printer's printable area: bed outline (mm) and maximum print height. */
data class BedShape(
    val outline: List<Pair<Double, Double>>,
    val maxHeight: Double,
) {
    /** Center of the outline's bounding box, where new objects are placed. */
    val center: Pair<Double, Double>
        get() {
            if (outline.isEmpty()) return 0.0 to 0.0
            val xs = outline.map { it.first }
            val ys = outline.map { it.second }
            return (xs.min() + xs.max()) / 2 to (ys.min() + ys.max()) / 2
        }
}
