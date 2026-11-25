package com.orcamobile.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.printer.PrinterRepository
import com.orcamobile.core.domain.printer.PrinterSessions
import com.orcamobile.core.model.PrinterLink
import com.orcamobile.core.model.PrinterState
import com.orcamobile.core.model.SavedPrinter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One printer in the list, with what it's doing now. */
data class PrinterCard(
    val printer: SavedPrinter,
    /** Null where its protocol only takes uploads (no live status). */
    val state: PrinterState?,
    val selected: Boolean,
)

data class PrintersUiState(val loading: Boolean = true, val printers: List<PrinterCard> = emptyList())

sealed interface PrintersIntent {
    /** Prints go to [id] by default. */
    data class Select(val id: String) : PrintersIntent
}

/** The Device tab's list: every saved printer with its live status. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PrintersViewModel @Inject constructor(
    private val repository: PrinterRepository,
    private val sessions: PrinterSessions,
) : ViewModel() {

    val state: StateFlow<PrintersUiState> = combine(repository.printers, repository.selected) { printers, selected -> printers to selected }
        .onEach { (printers, _) -> sessions.keepOnly(printers.map { it.id }.toSet()) }
        .flatMapLatest { (printers, selected) ->
            if (printers.isEmpty()) return@flatMapLatest flowOf(PrintersUiState(loading = false))
            val cards = printers.map { printer ->
                val connection = sessions.connection(printer)
                connection?.state?.map { PrinterCard(printer, it, printer.id == selected) }
                    ?: flowOf(PrinterCard(printer, null, printer.id == selected))
            }
            combine(cards) { PrintersUiState(loading = false, printers = it.toList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrintersUiState())

    fun onIntent(intent: PrintersIntent) {
        when (intent) {
            is PrintersIntent.Select -> viewModelScope.launch { repository.select(intent.id) }
        }
    }
}

/** Whether the app can reach the printer now. */
val PrinterState.online: Boolean get() = link is PrinterLink.Online
