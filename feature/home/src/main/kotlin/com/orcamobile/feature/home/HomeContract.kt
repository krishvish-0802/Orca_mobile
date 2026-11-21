package com.orcamobile.feature.home

import com.orcamobile.core.model.EngineInfo
import com.orcamobile.core.model.PresetChoices

/** Everything the start screen renders. Produced only by [HomeViewModel]. */
data class HomeUiState(
    val engine: EngineUiState = EngineUiState.Loading,
    val printerSetup: PrinterSetupUiState = PrinterSetupUiState(),
    val importing: Boolean = false,
    val importError: String? = null,
)

sealed interface EngineUiState {
    data object Loading : EngineUiState

    data class Ready(val info: EngineInfo) : EngineUiState

    data class Unavailable(val reason: String) : EngineUiState
}

/** Choosing the printer, process and filament presets: the app-wide choice (PresetPicker). */
typealias PrinterSetupUiState = PresetChoices

/** User actions on the start screen. */
sealed interface HomeIntent {
    data class SelectVendor(val vendor: String) : HomeIntent

    data class SelectPrinter(val printer: String) : HomeIntent

    data class SelectProcess(val process: String) : HomeIntent

    data class SelectFilament(val filament: String) : HomeIntent

    /** A document the user picked in the system file picker. */
    data class ImportModel(val uri: String) : HomeIntent

    data object OpenSample : HomeIntent
}

/** One-off effects the screen acts on. */
sealed interface HomeEvent {
    data class OpenModel(val path: String) : HomeEvent
}
