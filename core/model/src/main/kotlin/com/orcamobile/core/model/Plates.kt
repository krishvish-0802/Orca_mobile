package com.orcamobile.core.model

/**
 * One of the project's plates, as the desktop's PartPlate: a name and the
 * settings it prints with over the presets ([PlateSettingKeys]). All plates
 * share the printer's bed.
 */
data class PlateInfo(
    val name: String = "",
    val settings: Map<String, String> = emptyMap(),
    /** Instances on the plate. */
    val instances: Int = 0,
) {
    /** The name to show: the plate's own, or "Plate n" for plate [index]. */
    fun label(index: Int): String = name.ifBlank { "Plate ${index + 1}" }
}

/** The project's plates, which one is being edited, and every object with its instances' plates. */
data class Plates(
    val current: Int,
    val plates: List<PlateInfo>,
    val objects: List<ModelObjectInfo>,
)

/** A change to the project's plates. */
sealed interface PlateAction {
    /** Changes nothing: reads the plates. */
    data object Get : PlateAction

    /** Makes [plate] the one edited, shown and sliced. */
    data class Select(val plate: Int) : PlateAction

    /** Adds an empty plate after the last one and selects it. */
    data object Add : PlateAction

    /** Removes [plate] with everything on it. The last plate cannot be removed. */
    data class Remove(val plate: Int) : PlateAction

    data class Rename(val plate: Int, val name: String) : PlateAction

    /** Sets one of [PlateSettingKeys] for [plate], in the config's own text form. */
    data class SetSetting(val plate: Int, val key: String, val value: String) : PlateAction

    /** [plate] follows the presets again for [key]. */
    data class ResetSetting(val plate: Int, val key: String) : PlateAction

    /** Puts [instance] on [plate], where it lies on the bed. */
    data class Move(val instance: InstanceRef, val plate: Int) : PlateAction
}

/** The settings a plate may hold over the presets, as the desktop's plate settings dialog. */
object PlateSettingKeys {
    /** The build plate surface; the printer's temperatures for it follow. */
    const val BED_TYPE = "curr_bed_type"

    /** "by layer" or "by object". */
    const val PRINT_SEQUENCE = "print_sequence"

    /** "1" or "0". */
    const val SPIRAL_VASE = "spiral_mode"

    /** Where the plate's prime tower stands (its front left corner), mm; set by dragging it. */
    const val WIPE_TOWER_X = "wipe_tower_x"
    const val WIPE_TOWER_Y = "wipe_tower_y"

    /** Values of [BED_TYPE], as the config writes them, with their labels in the desktop. */
    val bedTypes: List<Pair<String, String>> = listOf(
        "Cool Plate" to "Smooth Cool Plate",
        "Engineering Plate" to "Engineering Plate",
        "High Temp Plate" to "Smooth High Temp Plate",
        "Textured PEI Plate" to "Textured PEI Plate",
        "Textured Cool Plate" to "Textured Cool Plate",
        "Supertack Plate" to "Cool Plate (SuperTack)",
    )

    const val BY_LAYER = "by layer"
    const val BY_OBJECT = "by object"
}

/**
 * The current plate's prime tower before slicing, as the desktop draws it: its
 * corner on the bed, its estimated footprint and brim (mm) and its turn about Z.
 */
data class WipeTower(
    val x: Double,
    val y: Double,
    val width: Double,
    val depth: Double,
    val height: Double,
    val brim: Double,
    val rotationDegrees: Double,
    /** It, brim included, meets an object or its brim: it would print into it. */
    val overlapsObjects: Boolean,
)
