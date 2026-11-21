package com.orcamobile.feature.prepare

import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.model.ProjectSummary
import com.orcamobile.core.model.SliceStatistics

/** Everything the Prepare screen renders. Produced only by [PrepareViewModel]. */
data class PrepareUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val project: ProjectSummary? = null,
    val bed: BedShape? = null,
    val presets: PresetSelection? = null,
    val slice: SliceUiState = SliceUiState.Idle,
    val printHost: PrintHostUiState = PrintHostUiState(),
)

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

    data object CancelSlice : PrepareIntent

    data class SetHostType(val type: PrintHostType) : PrepareIntent

    data class SetHostAddress(val address: String) : PrepareIntent

    data class SetHostApiKey(val apiKey: String) : PrepareIntent

    data class SetStartPrint(val startPrint: Boolean) : PrepareIntent

    data object TestPrintHost : PrepareIntent

    data object SendToPrinter : PrepareIntent
}

/** Navigation argument: the model file to open. */
const val PREPARE_MODEL_PATH_ARG = "modelPath"
