package com.orcamobile.feature.prepare

import com.orcamobile.core.model.ArrangeOptions
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.BuildPlateSide
import com.orcamobile.core.model.GCodePreviewSummary
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.InstanceTransform
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.TransformEdit
import com.orcamobile.core.viewport.HandleMode
import com.orcamobile.core.model.PresetChoices
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.ProjectSummary
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
) {
    /** Plate tools may change the plate: not while one runs, nor while slicing (the slice would be stale). */
    val plateEditable: Boolean
        get() = project != null && !loading && plateTask == null && !showingPreview && slice !is SliceUiState.Running

    /** Name of the selected object, for the hint over the plate. */
    val selectedObjectName: String?
        get() = selection?.let { project?.objects?.getOrNull(it.objectIndex)?.name }
}

enum class PlateTask { ARRANGE, ORIENT, COPY, FILL_BED, DELETE }

sealed interface PlateNotice {
    /** Arranging left [count] instances beside the bed: they did not fit. */
    data class NotPlaced(val count: Int) : PlateNotice

    /** [added] copies are on the bed; [beside] more did not fit and lie beside it. */
    data class CopiesAdded(val added: Int, val beside: Int) : PlateNotice

    /** Fill bed found no room for another copy. */
    data object BedFull : PlateNotice

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

    data class Done(val statistics: SliceStatistics, val gcodePath: String) : SliceUiState

    data object Canceled : SliceUiState

    data class Failed(val message: String) : SliceUiState
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

    data class SelectVendor(val vendor: String) : PrepareIntent

    data class SelectPrinter(val printer: String) : PrepareIntent

    data class SelectProcess(val process: String) : PrepareIntent

    data class SelectFilament(val filament: String) : PrepareIntent

    /** Objects were moved or turned on the plate: earlier slicing results no longer match it. */
    data object PlateEdited : PrepareIntent

    /** Packs every object on the bed. */
    data object Arrange : PrepareIntent

    data class SetArrangeOptions(val options: ArrangeOptions) : PrepareIntent

    /** Turns the selected object, or every object when none is selected, to print with the least support. */
    data object Orient : PrepareIntent

    /** Adds [count] copies of the selected object, or as many as fit when null. */
    data class AddCopies(val count: Int?) : PrepareIntent

    data object DeleteSelected : PrepareIntent

    data object DismissPlateNotice : PrepareIntent

    /** Changes the selected object's position, rotation, scale or mirroring. */
    data class EditTransform(val edit: TransformEdit) : PrepareIntent

    data class SetUniformScale(val uniform: Boolean) : PrepareIntent

    /** Move, rotate or scale with the handles at the plate's edge. */
    data class SetHandleMode(val mode: HandleMode) : PrepareIntent

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
