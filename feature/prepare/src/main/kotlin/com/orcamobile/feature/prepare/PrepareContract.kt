package com.orcamobile.feature.prepare

import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.GCodePreviewSummary
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
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
) {
    /** Name of the selected object, for the hint over the plate. */
    val selectedObjectName: String?
        get() = selection?.let { project?.objects?.getOrNull(it.objectIndex)?.name }
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

    /** Objects were moved or turned on the plate: earlier slicing results no longer match it. */
    data object PlateEdited : PrepareIntent

    data object CancelSlice : PrepareIntent

    /** Switches the plate between the model and the sliced toolpaths. */
    data class ShowPreview(val show: Boolean) : PrepareIntent

    data class SetPreviewLayers(val layers: IntRange) : PrepareIntent

    data class SetPreviewColoring(val coloring: ToolpathColoring) : PrepareIntent

    data class SetHostType(val type: PrintHostType) : PrepareIntent

    data class SetHostAddress(val address: String) : PrepareIntent

    data class SetHostApiKey(val apiKey: String) : PrepareIntent

    data class SetStartPrint(val startPrint: Boolean) : PrepareIntent

    data object TestPrintHost : PrepareIntent

    data object SendToPrinter : PrepareIntent
}

/** Navigation argument: the model file to open. */
const val PREPARE_MODEL_PATH_ARG = "modelPath"
