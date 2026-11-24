package com.orcamobile.feature.home

import com.orcamobile.core.domain.presets.UnsavedChoice
import com.orcamobile.core.domain.presets.UnsavedPrompt
import com.orcamobile.core.model.AppUpdate
import com.orcamobile.core.model.EngineInfo
import com.orcamobile.core.model.PresetChoices

/** Everything the start screen renders. Produced only by [HomeViewModel]. */
data class HomeUiState(
    val engine: EngineUiState = EngineUiState.Loading,
    val printerSetup: PrinterSetupUiState = PrinterSetupUiState(),
    val importing: Boolean = false,
    val importError: String? = null,
    /** A newer build of the app on GitHub, and how getting it is going. */
    val update: UpdateUiState = UpdateUiState.None,
    /** A preset change waiting on what to do with the changed settings. */
    val unsaved: UnsavedPrompt? = null,
)

sealed interface UpdateUiState {
    data object None : UpdateUiState

    data class Available(val update: AppUpdate) : UpdateUiState

    data class Downloading(val update: AppUpdate, val percent: Int?) : UpdateUiState

    /** Downloaded; Android's installer takes it from here. */
    data class Ready(val update: AppUpdate, val apkPath: String, val needsPermission: Boolean = false) : UpdateUiState

    data class Failed(val update: AppUpdate, val message: String) : UpdateUiState
}

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

    /** The answer to [HomeUiState.unsaved]; null cancels the change. */
    data class ResolveUnsaved(val choice: UnsavedChoice?) : HomeIntent

    /** A document the user picked in the system file picker. */
    data class ImportModel(val uri: String) : HomeIntent

    data object OpenSample : HomeIntent

    /** Looks for a newer build (on start and when the app comes back). */
    data object CheckForUpdate : HomeIntent

    data object DownloadUpdate : HomeIntent

    data object InstallUpdate : HomeIntent

    /** Hides the update until the next newer build. */
    data object DismissUpdate : HomeIntent
}

/** One-off effects the screen acts on. */
sealed interface HomeEvent {
    data class OpenModel(val path: String) : HomeEvent
}
