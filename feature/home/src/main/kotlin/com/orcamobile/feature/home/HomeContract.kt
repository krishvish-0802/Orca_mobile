package com.orcamobile.feature.home

import com.orcamobile.core.model.EngineInfo
import com.orcamobile.core.model.PresetSelection

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

/** Choosing the printer, process and filament presets to slice with. */
data class PrinterSetupUiState(
    val loading: Boolean = false,
    val vendors: List<String> = emptyList(),
    val vendor: String? = null,
    val printers: List<String> = emptyList(),
    val printer: String? = null,
    val processes: List<String> = emptyList(),
    val process: String? = null,
    val filaments: List<String> = emptyList(),
    val filament: String? = null,
    val error: String? = null,
) {
    /** The complete selection, or null while any part is missing. */
    val selection: PresetSelection?
        get() {
            val vendor = vendor ?: return null
            val printer = printer ?: return null
            val process = process ?: return null
            val filament = filament ?: return null
            return PresetSelection(vendor, printer, process, listOf(filament))
        }
}

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
