package com.orcamobile.feature.home

import com.orcamobile.core.model.EngineInfo
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.SliceStatistics

/** Everything the home screen renders. Produced only by [HomeViewModel]. */
data class HomeUiState(
    val engine: EngineUiState = EngineUiState.Loading,
    val printerSetup: PrinterSetupUiState = PrinterSetupUiState(),
    val slice: SliceUiState = SliceUiState.Idle,
)

/** Choosing the printer, process and filament presets for the test slice. */
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

sealed interface EngineUiState {
    data object Loading : EngineUiState

    data class Ready(val info: EngineInfo) : EngineUiState

    data class Unavailable(val reason: String) : EngineUiState
}

sealed interface SliceUiState {
    data object Idle : SliceUiState

    data class Running(val percent: Int, val message: String) : SliceUiState

    data class Done(val statistics: SliceStatistics, val gcodePath: String) : SliceUiState

    data object Canceled : SliceUiState

    data class Failed(val message: String) : SliceUiState
}

/** User actions on the home screen. */
sealed interface HomeIntent {
    data class SelectVendor(val vendor: String) : HomeIntent

    data class SelectPrinter(val printer: String) : HomeIntent

    data class SelectProcess(val process: String) : HomeIntent

    data class SelectFilament(val filament: String) : HomeIntent

    data object SliceSample : HomeIntent

    data object CancelSlice : HomeIntent
}
