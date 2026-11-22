package com.orcamobile.feature.prepare

import com.orcamobile.core.model.LayerRange
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.ObjectDetails
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrimitiveShape
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.TransformEdit
import com.orcamobile.core.model.VolumeInfo
import com.orcamobile.core.model.VolumeKind

/** The selected object's composition, for the object panel's Parts and Heights tabs. */
data class ObjectListUiState(
    val project: ProjectId? = null,
    val objectIndex: Int? = null,
    val details: ObjectDetails? = null,
    /** A volume picked in the list: highlighted on the plate, its placement editable. */
    val selectedVolume: Int? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val volume: VolumeInfo?
        get() = selectedVolume?.let { details?.volumes?.getOrNull(it) }
}

/** What the object list works on, from the Prepare screen. */
data class ObjectListContext(
    val project: ProjectId,
    val objectIndex: Int,
    val presets: PresetSelection?,
    /** The largest side of the bed, mm: new shapes are a tenth of it, as on the desktop. */
    val bedSizeMm: Double,
    /** Edits are refused while the plate is busy (a tool or a slice running). */
    val editable: Boolean,
    /** Grows when the plate changed the objects, so the list reads them again. */
    val revision: Int = 0,
)

sealed interface ObjectListIntent {
    /** Shows [context]'s object, or nothing. */
    data class Show(val context: ObjectListContext?) : ObjectListIntent

    data class SelectVolume(val volume: Int?) : ObjectListIntent

    data class AddShape(val kind: VolumeKind, val shape: PrimitiveShape) : ObjectListIntent

    /** A model file the user picked (a content URI), added as one volume. */
    data class AddFile(val kind: VolumeKind, val uri: String) : ObjectListIntent

    data class SetKind(val volume: Int, val kind: VolumeKind) : ObjectListIntent

    data class Rename(val volume: Int?, val name: String) : ObjectListIntent

    data class RemoveVolume(val volume: Int) : ObjectListIntent

    /** A change in the placement panel, applied to the selected volume within its object. */
    data class EditVolume(val edit: TransformEdit) : ObjectListIntent

    /** A new height range above the existing ones, with the slice's layer height. */
    data object AddRange : ObjectListIntent

    data class SetRange(val range: Int, val minZ: Double, val maxZ: Double) : ObjectListIntent

    data class SetRangeLayerHeight(val range: Int, val layerHeight: Double) : ObjectListIntent

    data class RemoveRange(val range: Int) : ObjectListIntent

    data object DismissError : ObjectListIntent

    /** Reads the object again (back from its settings): an earlier slice is stale if they changed. */
    data object Refresh : ObjectListIntent
}

/** One-off news for the Prepare screen. */
sealed interface ObjectListEvent {
    /** The object's shape or volumes changed: the plate redraws and an earlier slice is stale. */
    data class PlateChanged(val objects: List<ModelObjectInfo>) : ObjectListEvent

    /** Only settings changed: an earlier slice is stale, nothing to redraw. */
    data object SettingsChanged : ObjectListEvent
}

/** The height of a new range, mm, as the desktop's default. */
internal const val NEW_RANGE_HEIGHT_MM = 2.0

internal fun LayerRange.layerHeight(): String? = settings["layer_height"]

/** Settings changed by the user (a new part's extruder is the app's, not theirs). */
internal fun Map<String, String>.userChanges(): Int = keys.count { it != "extruder" }

/** Every setting of the object, its volumes and its height ranges, to tell whether any changed. */
internal fun ObjectDetails.allSettings(): List<Map<String, String>> =
    listOf(settings) + volumes.map { it.settings } + layerRanges.map { it.settings }
