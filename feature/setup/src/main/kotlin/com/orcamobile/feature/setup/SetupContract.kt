package com.orcamobile.feature.setup

import com.orcamobile.core.model.PresetChoices
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType

/** The first-run setup's pages, in order, as the desktop's Setup Wizard. */
enum class SetupStep { PRINTER, FILAMENTS, CONNECTION }

/** Everything the setup renders. Produced only by [SetupViewModel]. */
data class SetupUiState(
    val step: SetupStep = SetupStep.PRINTER,
    /** The printer chosen so far (the app-wide choice). */
    val choices: PresetChoices = PresetChoices(),
    /** The system filaments the printer suits, which the filament page offers. */
    val filaments: List<String> = emptyList(),
    /** Those of [filaments] the lists will show. */
    val shown: Set<String> = emptySet(),
    val filamentQuery: String = "",
    val host: HostForm = HostForm(),
    val error: String? = null,
) {
    val canGoOn: Boolean
        get() = when (step) {
            SetupStep.PRINTER -> choices.printer != null && !choices.loading
            SetupStep.FILAMENTS -> shown.isNotEmpty()
            SetupStep.CONNECTION -> true
        }

    val shownFilaments: List<String>
        get() = if (filamentQuery.isBlank()) filaments else filaments.filter { it.contains(filamentQuery.trim(), ignoreCase = true) }
}

/** How the phone reaches the printer, entered on the last page (optional). */
data class HostForm(
    val type: PrintHostType = PrintHostType.MOONRAKER,
    val address: String = "",
    val apiKey: String = "",
    val test: HostTest = HostTest.Idle,
) {
    val config: PrintHostConfig?
        get() = address.trim().takeIf { it.isNotEmpty() }?.let { PrintHostConfig(type, it, apiKey.trim().ifEmpty { null }) }
}

sealed interface HostTest {
    data object Idle : HostTest

    data object Testing : HostTest

    data class Passed(val description: String) : HostTest

    data class Failed(val message: String) : HostTest
}

sealed interface SetupIntent {
    data class SelectVendor(val vendor: String) : SetupIntent

    data class SelectPrinter(val printer: String) : SetupIntent

    data class ToggleFilament(val name: String) : SetupIntent

    /** Shows all the filaments (true) or none (false). */
    data class ShowAllFilaments(val all: Boolean) : SetupIntent

    data class SearchFilaments(val query: String) : SetupIntent

    data class SetHostType(val type: PrintHostType) : SetupIntent

    data class SetHostAddress(val address: String) : SetupIntent

    data class SetHostApiKey(val apiKey: String) : SetupIntent

    data object TestHost : SetupIntent

    data object Next : SetupIntent

    data object Back : SetupIntent

    /** Leaves without finishing; the setup isn't offered again. */
    data object Skip : SetupIntent
}

sealed interface SetupEvent {
    data object Finished : SetupEvent
}
