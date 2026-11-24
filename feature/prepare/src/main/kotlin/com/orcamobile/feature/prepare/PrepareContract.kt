package com.orcamobile.feature.prepare

import com.orcamobile.core.model.ArrangeOptions
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.BuildPlateSide
import com.orcamobile.core.model.GCodePreviewSummary
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.InstanceTransform
import com.orcamobile.core.model.PaintBrush
import com.orcamobile.core.model.PaintKind
import com.orcamobile.core.model.EmbossTarget
import com.orcamobile.core.model.PlateInfo
import com.orcamobile.core.model.WipeTower
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.TransformEdit
import com.orcamobile.core.viewport.HandleMode
import com.orcamobile.core.model.PresetChoices
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.ProjectSummary
import com.orcamobile.core.model.SimplifyCounts
import com.orcamobile.core.model.SimplifySettings
import com.orcamobile.core.model.SliceStatistics
import com.orcamobile.core.model.ToolpathColoring

/** Everything the Prepare screen renders. Produced only by [PrepareViewModel]. */
data class PrepareUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val project: ProjectSummary? = null,
    val bed: BedShape? = null,
    val presets: PresetSelection? = null,
    val slice: SliceUiState = SliceUiState.Idle,
    val printHost: PrintHostUiState = PrintHostUiState(),
    val preview: PreviewUiState = PreviewUiState.None,
    /** The plate shows the sliced toolpaths rather than the model. */
    val showingPreview: Boolean = false,
    /** The object instance being edited on the plate. */
    val selection: InstanceRef? = null,
    /** Other instances selected with [selection] (long press): they move, turn, align and delete with it. */
    val group: List<InstanceRef> = emptyList(),
    /** How many settings the user changed from the presets (see the settings screen). */
    val changedSettings: Int = 0,
    /** The app-wide preset choice, changed from the chips under the plate. */
    val choices: PresetChoices = PresetChoices(),
    /** Grows when the engine moved the objects (e.g. onto a new printer's bed), so the plate redraws them. */
    val plateRevision: Int = 0,
    /** A plate tool running in the engine; the plate cannot be edited meanwhile. */
    val plateTask: PlateTask? = null,
    val arrangeOptions: ArrangeOptions = ArrangeOptions(),
    /** What the last plate tool did, shown briefly over the plate. */
    val plateNotice: PlateNotice? = null,
    /** The selected instance's transform, for the object panel. */
    val transform: InstanceTransform? = null,
    /** Scaling one axis scales all three. */
    val uniformScale: Boolean = true,
    /** The next tap on an object lays it on the side tapped. */
    val layingOnFace: Boolean = false,
    /** What the handles at the plate's edge do for the selection. */
    val handleMode: HandleMode = HandleMode.MOVE,
    /** Painting the selected object with this brush; null when not painting. */
    val paint: PaintBrush? = null,
    /** Grows to erase everything painted of the brush's kind on the selected object. */
    val paintClearRequest: Int = 0,
    /**
     * A mesh tool open on the selected object; the plate belongs to it meanwhile
     * (the plate tools are hidden and the plate does not move the objects).
     */
    val meshTool: MeshTool? = null,
    /** Simplify's settings, kept between uses, and what the preview would leave. */
    val simplify: SimplifySettings = SimplifySettings(),
    val simplifyCounts: SimplifyCounts? = null,
    /** The project's plates; the plate shows, edits and slices [currentPlate]. */
    val plates: List<PlateInfo> = listOf(PlateInfo()),
    val currentPlate: Int = 0,
    /** Plates with a slice that still matches them: switching to one shows its result again. */
    val slicedPlates: Set<Int> = emptySet(),
    /** Slicing every plate: which one of how many is being sliced (1-based), null otherwise. */
    val slicingAll: Pair<Int, Int>? = null,
    /** The current plate's prime tower as the slice will place it; null when it prints none. */
    val wipeTower: WipeTower? = null,
    /** A text or SVG to open in the Text tool for editing (from the Parts tab). */
    val textToEdit: EmbossTarget? = null,
    /** The project's undo history has a step back, or forward. */
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Grows with each undo or redo, so the tools open on the plate read the project again. */
    val historyRevision: Int = 0,
    /** Grows when the model's paint changed under the brush (undo, a bake, paint from the engine): painting reads it again. */
    val paintRevision: Int = 0,
    /** Grows with each stroke that changed the paint. */
    val paintStrokes: Int = 0,
) {
    /**
     * Undo and redo: on the plate, while painting and in the Text tool (which
     * read the project again after a step); not in the other tools, mid-slice
     * or in the preview.
     */
    val historyUsable: Boolean
        get() = project != null && !loading && plateTask == null && !showingPreview && slice !is SliceUiState.Running &&
            (meshTool == null || meshTool == MeshTool.EMBOSS)


    /** Another plate may be shown: not while a tool, a slice or painting holds this one. */
    val canSwitchPlate: Boolean
        get() = project != null && !loading && plateTask == null && slice !is SliceUiState.Running &&
            paint == null && meshTool == null

    /** Instances on [plate], from the objects' own plate list. */
    fun instancesOn(plate: Int): Int =
        project?.objects?.sumOf { o -> (0 until o.instances).count { o.plateOf(it) == plate } } ?: 0

    /** Plates with something on them, which "Slice all" slices. */
    val platesToSlice: List<Int>
        get() = plates.indices.filter { instancesOn(it) > 0 }

    /** Plate tools may change the plate: not while one runs, nor while slicing (the slice would be stale). */
    val plateEditable: Boolean
        get() = project != null && !loading && plateTask == null && !showingPreview && slice !is SliceUiState.Running &&
            paint == null

    /** The paint kinds offered: colour needs more than one filament. */
    val paintKinds: List<PaintKind>
        get() = PaintKind.entries.filter { it != PaintKind.COLOR || (presets?.filaments?.size ?: 1) > 1 }

    /** Every selected instance, the main one first. */
    val selected: List<InstanceRef>
        get() = listOfNotNull(selection) + group

    /** Name of the selected object, for the hint over the plate. */
    val selectedObjectName: String?
        get() = selection?.let { project?.objects?.getOrNull(it.objectIndex)?.name }
}

enum class PlateTask {
    ARRANGE,

    /** Arrange all plates, adding plates for what does not fit. */
    ARRANGE_ALL,
    ORIENT,
    COPY,
    FILL_BED,
    DELETE,
    SPLIT,
    ALIGN,

    /** Adding, removing, switching or changing a plate. */
    PLATES,
}

/** The desktop's mesh tools, each with a view model of its own. */
enum class MeshTool {
    CUT,
    SIMPLIFY,

    /** Measure and assembly; needs no selection. */
    MEASURE,

    /** Variable layer height. */
    LAYERS,
    BRIM_EARS,

    /** Text and SVG; without a selection only as new objects. */
    EMBOSS,
}

sealed interface PlateNotice {
    /** Arranging left [count] instances beside the bed: they did not fit. */
    data class NotPlaced(val count: Int) : PlateNotice

    /** [added] copies are on the bed; [beside] more did not fit and lie beside it. */
    data class CopiesAdded(val added: Int, val beside: Int) : PlateNotice

    /** The object came apart into [count] objects. */
    data class Split(val count: Int) : PlateNotice

    /** A cut left [count] objects. */
    data class Cut(val count: Int) : PlateNotice

    /** Fill bed found no room for another copy. */
    data object BedFull : PlateNotice

    /** Arrange all plates used [plates] plates. */
    data class ArrangedAll(val plates: Int) : PlateNotice

    /** The selected object went to another plate, [plate] (0-based). */
    data class MovedToPlate(val plate: Int) : PlateNotice

    data class Failed(val message: String) : PlateNotice
}

/** The sliced plate's toolpaths, read back from the G-code. */
sealed interface PreviewUiState {
    data object None : PreviewUiState

    data object Loading : PreviewUiState

    data class Ready(
        val summary: GCodePreviewSummary,
        /** Shown layers, 0-based and inclusive. */
        val layers: IntRange,
        val coloring: ToolpathColoring = ToolpathColoring.FeatureType,
    ) : PreviewUiState

    data class Failed(val message: String) : PreviewUiState
}

sealed interface SliceUiState {
    data object Idle : SliceUiState

    data class Running(val percent: Int, val message: String) : SliceUiState

    /** Sliced; [warnings] are what the slicer would have the user know. */
    data class Done(
        val statistics: SliceStatistics,
        val gcodePath: String,
        val warnings: List<com.orcamobile.core.model.SliceMessage> = emptyList(),
    ) : SliceUiState

    data object Canceled : SliceUiState

    /** Not sliced: [message], and per object in [details] with the warnings before it. */
    data class Failed(val message: String, val details: List<com.orcamobile.core.model.SliceMessage> = emptyList()) : SliceUiState
}

/** A one-tap way to act on something the slicer said. */
sealed interface SliceFix {
    /** Turns supports on (the presets' change) and slices again. */
    data object TurnOnSupports : SliceFix

    /** Turns the object to its best side down (auto-orient). */
    data class LayFlat(val objectIndex: Int) : SliceFix

    /** Selects the object, to see it on the plate. */
    data class Show(val objectIndex: Int) : SliceFix

    /** Opens the setting the message is about (the screen navigates). */
    data class OpenSetting(val key: String) : SliceFix
}

/** Sending the sliced G-code to a printer host. */
data class PrintHostUiState(
    val type: PrintHostType = PrintHostType.MOONRAKER,
    val address: String = "",
    val apiKey: String = "",
    val startPrint: Boolean = false,
    /** For hosts that take them ([PrintHostType.supportsStartOptions]); remembered between sends. */
    val startOptions: PrintStartOptions = PrintStartOptions(),
    val status: PrintHostStatus = PrintHostStatus.Idle,
) {
    val config: PrintHostConfig?
        get() = address.takeIf { it.isNotBlank() }?.let { PrintHostConfig(type, it, apiKey.ifBlank { null }) }
}

sealed interface PrintHostStatus {
    data object Idle : PrintHostStatus

    data object Testing : PrintHostStatus

    data class Connected(val description: String) : PrintHostStatus

    data class Sending(val percent: Int) : PrintHostStatus

    data class Sent(val printing: Boolean) : PrintHostStatus

    data class Failed(val message: String) : PrintHostStatus
}

sealed interface PrepareIntent {
    data object Slice : PrepareIntent

    data class Select(val instance: InstanceRef?) : PrepareIntent

    /** Goes back a step in the project's history (a two-finger tap). */
    data object Undo : PrepareIntent

    /** Goes forward a step again (a three-finger tap). */
    data object Redo : PrepareIntent

    /** Adds [instance] to the selection, or takes it out (a long press). */
    data class ToggleSelected(val instance: InstanceRef) : PrepareIntent

    /** Lines up the selected objects, or puts them on the plate's edge or middle with [toPlate]. */
    data class Align(val mode: com.orcamobile.core.model.AlignMode, val toPlate: Boolean) : PrepareIntent

    data class SelectVendor(val vendor: String) : PrepareIntent

    data class SelectPrinter(val printer: String) : PrepareIntent

    data class SelectProcess(val process: String) : PrepareIntent

    /** Loads [filament] as filament [slot] + 1. */
    data class SelectFilament(val filament: String, val slot: Int = 0) : PrepareIntent

    /** Adds a filament, a copy of the last. */
    data object AddFilament : PrepareIntent

    /** Takes filament [slot] + 1 away; what used it falls back to filament 1. */
    data class RemoveFilament(val slot: Int) : PrepareIntent

    /** Filament [slot] + 1 prints in [colour] ("#RRGGBB"), or its preset's colour when null. */
    data class SetFilamentColour(val slot: Int, val colour: String?) : PrepareIntent

    /** Objects were moved or turned on the plate: earlier slicing results no longer match it. */
    data object PlateEdited : PrepareIntent

    /** Packs every object on the bed. */
    data object Arrange : PrepareIntent

    data class SetArrangeOptions(val options: ArrangeOptions) : PrepareIntent

    /** Turns the selected object, or every object when none is selected, to print with the least support. */
    data object Orient : PrepareIntent

    /** Acts on something the slicer said ([SliceFix.OpenSetting] is the screen's). */
    data class ApplySliceFix(val fix: SliceFix) : PrepareIntent

    /** Adds [count] copies of the selected object, or as many as fit when null. */
    data class AddCopies(val count: Int?) : PrepareIntent

    data object DeleteSelected : PrepareIntent

    /** The selected object becomes one object per separate piece (per part, when it has several). */
    data object SplitToObjects : PrepareIntent

    data object DismissPlateNotice : PrepareIntent

    /** Shows, edits and slices [plate]; its last slice comes back if it still matches. */
    data class SelectPlate(val plate: Int) : PrepareIntent

    /** Adds an empty plate and shows it. */
    data object AddPlate : PrepareIntent

    /** Removes [plate] and everything on it. */
    data class RemovePlate(val plate: Int) : PrepareIntent

    data class RenamePlate(val plate: Int, val name: String) : PrepareIntent

    /** Sets a plate's own setting ([com.orcamobile.core.model.PlateSettingKeys]), or follows the presets again when [value] is null. */
    data class SetPlateSetting(val plate: Int, val key: String, val value: String?) : PrepareIntent

    /** Puts the selected object on [plate], or on a new plate when null. */
    data class MoveToPlate(val plate: Int?) : PrepareIntent

    /** Packs every plate's objects, plate after plate, adding plates as needed. */
    data object ArrangeAllPlates : PrepareIntent

    /** Slices every plate with something on it, one after another. */
    data object SliceAll : PrepareIntent

    /** The prime tower was dragged to ([x], [y]) on the current plate. */
    data class MoveWipeTower(val x: Double, val y: Double) : PrepareIntent

    /** Opens a mesh tool on the selected object. */
    data class OpenMeshTool(val tool: MeshTool) : PrepareIntent

    data object CloseMeshTool : PrepareIntent

    /** Opens the Text tool on volume [volume] of the selected object, a text or SVG, to edit it. */
    data class EditText(val volume: Int) : PrepareIntent

    data class SetSimplify(val settings: SimplifySettings) : PrepareIntent

    /** The plate previewed Simplify. */
    data class SimplifyCounted(val counts: SimplifyCounts) : PrepareIntent

    /** The cut tool replaced the object by [pieces] objects; the plate holds [objects] now. */
    data class Cut(val objects: List<ModelObjectInfo>, val pieces: Int) : PrepareIntent

    /** Changes the selected object's position, rotation, scale or mirroring. */
    data class EditTransform(val edit: TransformEdit) : PrepareIntent

    data class SetUniformScale(val uniform: Boolean) : PrepareIntent

    /** Move, rotate or scale with the handles at the plate's edge. */
    data class SetHandleMode(val mode: HandleMode) : PrepareIntent

    /** Paints [kind] on the selected object until [StopPainting]. */
    data class StartPainting(val kind: PaintKind, val modifier: Int? = null) : PrepareIntent

    /** The first stroke of a new painted modifier made it: volume [volume] of the selected object, painted from now on. */
    data class PaintedModifierMade(val volume: Int) : PrepareIntent

    data class SetBrush(val brush: PaintBrush) : PrepareIntent

    data object ClearPaint : PrepareIntent

    data object StopPainting : PrepareIntent

    /** A stroke changed what will be sliced. */
    data object Painted : PrepareIntent

    /** The engine changed the paint itself (a texture over the whole object, a removed layer): the brush reads it again. */
    data object PaintReloaded : PrepareIntent

    /** A texture bake made the relief the object's mesh; the plate holds [objects]. */
    data class TextureBaked(val objects: List<ModelObjectInfo>) : PrepareIntent

    /** Turns on (or off) laying the next tapped object on the side tapped. */
    data class SetLayOnFace(val on: Boolean) : PrepareIntent

    /** The plate laid [instance] on a face. */
    data class LaidOnFace(val instance: InstanceRef) : PrepareIntent

    /** The object list changed an object's shape or volumes: redraw, and an earlier slice is stale. */
    data class ObjectChanged(val objects: List<ModelObjectInfo>) : PrepareIntent

    /** The object list changed only settings: an earlier slice is stale. */
    data object ObjectSettingsChanged : PrepareIntent

    data object CancelSlice : PrepareIntent

    /** Switches the plate between the model and the sliced toolpaths. */
    data class ShowPreview(val show: Boolean) : PrepareIntent

    data class SetPreviewLayers(val layers: IntRange) : PrepareIntent

    data class SetPreviewColoring(val coloring: ToolpathColoring) : PrepareIntent

    data class SetHostType(val type: PrintHostType) : PrepareIntent

    data class SetHostAddress(val address: String) : PrepareIntent

    data class SetHostApiKey(val apiKey: String) : PrepareIntent

    data class SetStartPrint(val startPrint: Boolean) : PrepareIntent

    data class SetTimeLapse(val enabled: Boolean) : PrepareIntent

    data class SetHeatedBedLeveling(val enabled: Boolean) : PrepareIntent

    data class SetBuildPlate(val side: BuildPlateSide) : PrepareIntent

    data object TestPrintHost : PrepareIntent

    data object SendToPrinter : PrepareIntent
}

/** Navigation argument: the model file to open. */
const val PREPARE_MODEL_PATH_ARG = "modelPath"
