package com.orcamobile.feature.devices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.printer.PrinterDiscovery
import com.orcamobile.core.domain.printer.PrinterRepository
import com.orcamobile.core.domain.usecase.TestPrintHostUseCase
import com.orcamobile.core.model.DiscoveredPrinter
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.model.SavedPrinter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** The argument naming the printer to edit; absent to add one. */
const val DEVICES_PRINTER_ARG = "printer"

data class EditPrinterUiState(
    /** Editing a saved printer (its id), or adding one (null). */
    val editing: String? = null,
    val name: String = "",
    val type: PrintHostType = PrintHostType.ELEGOO_LINK,
    val address: String = "",
    val apiKey: String = "",
    val scanning: Boolean = false,
    val found: List<DiscoveredPrinter> = emptyList(),
    val test: ConnectionTest = ConnectionTest.Idle,
) {
    val host: PrintHostConfig?
        get() = address.trim().takeIf { it.isNotEmpty() }?.let { PrintHostConfig(type, it, apiKey.trim().ifEmpty { null }) }

    val canSave: Boolean get() = host != null
}

sealed interface ConnectionTest {
    data object Idle : ConnectionTest

    data object Testing : ConnectionTest

    data class Passed(val description: String) : ConnectionTest

    data class Failed(val message: String) : ConnectionTest
}

sealed interface EditPrinterIntent {
    data object Scan : EditPrinterIntent

    /** Fills the form from a printer found on the network. */
    data class Use(val printer: DiscoveredPrinter) : EditPrinterIntent

    data class SetName(val name: String) : EditPrinterIntent

    data class SetType(val type: PrintHostType) : EditPrinterIntent

    data class SetAddress(val address: String) : EditPrinterIntent

    data class SetApiKey(val apiKey: String) : EditPrinterIntent

    data object Test : EditPrinterIntent

    data object Save : EditPrinterIntent

    data object Remove : EditPrinterIntent
}

sealed interface EditPrinterEvent {
    data object Done : EditPrinterEvent

    data object Removed : EditPrinterEvent
}

/** Adding a printer (found on the network or by address), or changing or removing one. */
@HiltViewModel
class EditPrinterViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PrinterRepository,
    private val discovery: PrinterDiscovery,
    private val testHost: TestPrintHostUseCase,
) : ViewModel() {
    private val editingId: String? = savedStateHandle.get<String>(DEVICES_PRINTER_ARG)?.takeIf { it.isNotEmpty() }
    private val _state = MutableStateFlow(EditPrinterUiState(editing = editingId))
    val state: StateFlow<EditPrinterUiState> = _state.asStateFlow()

    private val _events = Channel<EditPrinterEvent>(Channel.BUFFERED)
    val events: Flow<EditPrinterEvent> = _events.receiveAsFlow()

    private var scanJob: Job? = null

    init {
        if (editingId != null) {
            viewModelScope.launch {
                repository.printers.first().firstOrNull { it.id == editingId }?.let { printer ->
                    _state.update {
                        it.copy(name = printer.name, type = printer.host.type, address = printer.host.baseUrl, apiKey = printer.host.apiKey.orEmpty())
                    }
                }
            }
        } else {
            scan()
        }
    }

    fun onIntent(intent: EditPrinterIntent) {
        when (intent) {
            EditPrinterIntent.Scan -> scan()
            is EditPrinterIntent.Use -> _state.update {
                it.copy(name = intent.printer.name, type = intent.printer.host.type, address = intent.printer.host.baseUrl, test = ConnectionTest.Idle)
            }
            is EditPrinterIntent.SetName -> _state.update { it.copy(name = intent.name) }
            is EditPrinterIntent.SetType -> _state.update { it.copy(type = intent.type, test = ConnectionTest.Idle) }
            is EditPrinterIntent.SetAddress -> _state.update { it.copy(address = intent.address, test = ConnectionTest.Idle) }
            is EditPrinterIntent.SetApiKey -> _state.update { it.copy(apiKey = intent.apiKey, test = ConnectionTest.Idle) }
            EditPrinterIntent.Test -> test()
            EditPrinterIntent.Save -> save()
            EditPrinterIntent.Remove -> editingId?.let { id ->
                viewModelScope.launch {
                    repository.remove(id)
                    _events.send(EditPrinterEvent.Removed)
                }
            }
        }
    }

    private fun scan() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = true, found = emptyList()) }
        scanJob = viewModelScope.launch {
            val saved = repository.printers.first().map { it.host.baseUrl }.toSet()
            runCatching {
                discovery.discover(SCAN_MILLIS).collect { printer ->
                    if (printer.host.baseUrl !in saved) _state.update { it.copy(found = it.found + printer) }
                }
            }
            _state.update { it.copy(scanning = false) }
        }
    }

    private fun test() {
        val host = _state.value.host ?: return
        _state.update { it.copy(test = ConnectionTest.Testing) }
        viewModelScope.launch {
            val result = testHost(host)
            _state.update { state ->
                if (state.host != host) return@update state
                state.copy(test = result.fold({ ConnectionTest.Passed(it.description) }, { ConnectionTest.Failed(it.message ?: it.javaClass.simpleName) }))
            }
        }
    }

    private fun save() {
        val state = _state.value
        val host = state.host ?: return
        viewModelScope.launch {
            val id = editingId ?: UUID.randomUUID().toString()
            val existing = repository.printers.first().firstOrNull { it.id == id }
            repository.save(SavedPrinter(id, state.name.trim().ifEmpty { host.type.displayName }, host, existing?.preset))
            _events.send(EditPrinterEvent.Done)
        }
    }

    private companion object {
        const val SCAN_MILLIS = 5_000L
    }
}
