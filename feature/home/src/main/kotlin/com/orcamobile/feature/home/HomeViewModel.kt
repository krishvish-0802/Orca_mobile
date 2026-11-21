package com.orcamobile.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.usecase.GetEngineStatusUseCase
import com.orcamobile.core.domain.usecase.SendGcodeUseCase
import com.orcamobile.core.domain.usecase.SliceSampleModelUseCase
import com.orcamobile.core.domain.usecase.TestPrintHostUseCase
import com.orcamobile.core.model.EngineStatus
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getEngineStatus: GetEngineStatusUseCase,
    private val sliceSampleModel: SliceSampleModelUseCase,
    private val presets: PresetCatalog,
    private val testPrintHost: TestPrintHostUseCase,
    private val sendGcode: SendGcodeUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var sliceJob: Job? = null
    private var presetJob: Job? = null
    private var hostJob: Job? = null

    init {
        val engine = getEngineStatus().toUiState()
        _state.update { it.copy(engine = engine) }
        if (engine is EngineUiState.Ready) loadVendors()
    }

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            is HomeIntent.SelectVendor -> selectVendor(intent.vendor)
            is HomeIntent.SelectPrinter -> selectPrinter(intent.printer)
            is HomeIntent.SelectProcess -> updateSetup { copy(process = intent.process) }
            is HomeIntent.SelectFilament -> updateSetup { copy(filament = intent.filament) }
            is HomeIntent.SetHostType -> updateHost { copy(type = intent.type, status = PrintHostStatus.Idle) }
            is HomeIntent.SetHostAddress -> updateHost { copy(address = intent.address, status = PrintHostStatus.Idle) }
            is HomeIntent.SetHostApiKey -> updateHost { copy(apiKey = intent.apiKey, status = PrintHostStatus.Idle) }
            is HomeIntent.SetStartPrint -> updateHost { copy(startPrint = intent.startPrint) }
            HomeIntent.TestPrintHost -> testHost()
            HomeIntent.SendToPrinter -> sendToPrinter()
            HomeIntent.SliceSample -> startSlice()
            HomeIntent.CancelSlice -> cancelSlice()
        }
    }

    // --- printer setup ------------------------------------------------------

    private fun loadVendors() = loadPresets {
        presets.vendors().fold(
            { vendors -> updateSetup { copy(loading = false, vendors = vendors) } },
            ::showPresetError,
        )
    }

    private fun selectVendor(vendor: String) {
        updateSetup {
            PrinterSetupUiState(loading = true, vendors = vendors, vendor = vendor)
        }
        loadPresets {
            presets.printers(vendor).fold(
                { printers -> updateSetup { copy(loading = false, printers = printers) } },
                ::showPresetError,
            )
        }
    }

    private fun selectPrinter(printer: String) {
        val vendor = _state.value.printerSetup.vendor ?: return
        updateSetup {
            copy(loading = true, printer = printer, processes = emptyList(), process = null,
                filaments = emptyList(), filament = null, error = null)
        }
        loadPresets {
            presets.compatiblePresets(vendor, printer).fold(
                { compatible ->
                    updateSetup {
                        copy(
                            loading = false,
                            processes = compatible.processes,
                            process = compatible.defaultProcess,
                            filaments = compatible.filaments,
                            filament = compatible.defaultFilament,
                        )
                    }
                },
                ::showPresetError,
            )
        }
    }

    /** Runs one preset lookup at a time; a newer choice supersedes an older lookup. */
    private fun loadPresets(block: suspend () -> Unit) {
        presetJob?.cancel()
        presetJob = viewModelScope.launch { block() }
    }

    private fun showPresetError(error: Throwable) {
        updateSetup { copy(loading = false, error = error.message ?: "Could not load presets") }
    }

    private fun updateSetup(transform: PrinterSetupUiState.() -> PrinterSetupUiState) {
        _state.update { it.copy(printerSetup = it.printerSetup.transform()) }
    }

    // --- sending to a printer -----------------------------------------------

    private fun testHost() {
        val host = _state.value.printHost.config ?: return
        runHostTask(PrintHostStatus.Testing) {
            testPrintHost(host).fold(
                { PrintHostStatus.Connected(it.description) },
                { PrintHostStatus.Failed(it.message ?: "Connection failed") },
            )
        }
    }

    private fun sendToPrinter() {
        val host = _state.value.printHost.config ?: return
        val gcode = (_state.value.slice as? SliceUiState.Done)?.gcodePath ?: return
        val startPrint = _state.value.printHost.startPrint
        runHostTask(PrintHostStatus.Sending(0)) {
            sendGcode(host, gcode, startPrint) { sent, total ->
                val percent = if (total > 0) (sent * 100 / total).toInt() else 0
                updateHost { if (status is PrintHostStatus.Sending) copy(status = PrintHostStatus.Sending(percent)) else this }
            }.fold(
                { PrintHostStatus.Sent(startPrint) },
                { PrintHostStatus.Failed(it.message ?: "Upload failed") },
            )
        }
    }

    private fun runHostTask(initial: PrintHostStatus, task: suspend () -> PrintHostStatus) {
        if (hostJob?.isActive == true) return
        updateHost { copy(status = initial) }
        hostJob = viewModelScope.launch {
            val result = task()
            updateHost { copy(status = result) }
        }
    }

    private fun updateHost(transform: PrintHostUiState.() -> PrintHostUiState) {
        _state.update { it.copy(printHost = it.printHost.transform()) }
    }

    // --- slicing ------------------------------------------------------------

    private fun startSlice() {
        if (sliceJob?.isActive == true) return
        val selection = _state.value.printerSetup.selection
        _state.update { it.copy(slice = SliceUiState.Running(0, "Preparing")) }
        sliceJob = viewModelScope.launch {
            sliceSampleModel(selection).collect { event ->
                _state.update { it.copy(slice = event.toUiState()) }
            }
        }
    }

    private fun cancelSlice() {
        val job = sliceJob ?: return
        if (!job.isActive) return
        job.cancel()
        _state.update { it.copy(slice = SliceUiState.Canceled) }
    }

    private fun EngineStatus.toUiState(): EngineUiState = when (this) {
        is EngineStatus.Available -> EngineUiState.Ready(info)
        is EngineStatus.Unavailable -> EngineUiState.Unavailable(reason)
    }

    private fun SliceEvent.toUiState(): SliceUiState = when (this) {
        is SliceEvent.Progress -> SliceUiState.Running(percent, message)
        is SliceEvent.Finished -> when (val r = result) {
            is SliceResult.Succeeded -> SliceUiState.Done(r.statistics, r.gcodePath)
            SliceResult.Canceled -> SliceUiState.Canceled
            is SliceResult.Failed -> SliceUiState.Failed(r.message)
        }
    }
}
