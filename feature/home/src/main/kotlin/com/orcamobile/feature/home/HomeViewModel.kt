package com.orcamobile.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.model.ModelImportRepository
import com.orcamobile.core.domain.model.SampleModelRepository
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.usecase.GetEngineStatusUseCase
import com.orcamobile.core.model.EngineStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getEngineStatus: GetEngineStatusUseCase,
    private val presets: PresetCatalog,
    private val settings: UserSettingsRepository,
    private val imports: ModelImportRepository,
    private val samples: SampleModelRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    private var presetJob: Job? = null

    init {
        val engine = getEngineStatus().toUiState()
        _state.update { it.copy(engine = engine) }
        if (engine is EngineUiState.Ready) loadVendors()
    }

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            is HomeIntent.SelectVendor -> selectVendor(intent.vendor)
            is HomeIntent.SelectPrinter -> selectPrinter(intent.printer)
            is HomeIntent.SelectProcess -> {
                updateSetup { copy(process = intent.process) }
                rememberSelection()
            }
            is HomeIntent.SelectFilament -> {
                updateSetup { copy(filament = intent.filament) }
                rememberSelection()
            }
            is HomeIntent.ImportModel -> importModel(intent.uri)
            HomeIntent.OpenSample -> viewModelScope.launch { _events.send(HomeEvent.OpenModel(samples.calibrationCubePath())) }
        }
    }

    // --- opening models -----------------------------------------------------

    private fun importModel(uri: String) {
        if (_state.value.importing) return
        _state.update { it.copy(importing = true, importError = null) }
        viewModelScope.launch {
            imports.import(uri).fold(
                { path ->
                    _state.update { it.copy(importing = false) }
                    _events.send(HomeEvent.OpenModel(path))
                },
                { error -> _state.update { it.copy(importing = false, importError = error.message ?: "Import failed") } },
            )
        }
    }

    // --- printer setup ------------------------------------------------------

    /** Loads the vendor list, then restores the last saved selection if it still exists. */
    private fun loadVendors() = loadPresets {
        updateSetup { copy(loading = true) }
        val vendors = presets.vendors().getOrElse { return@loadPresets showPresetError(it) }
        updateSetup { copy(loading = false, vendors = vendors) }
        val saved = settings.presetSelection() ?: return@loadPresets
        if (saved.vendor !in vendors) return@loadPresets
        val printers = presets.printers(saved.vendor).getOrElse { return@loadPresets showPresetError(it) }
        updateSetup { copy(vendor = saved.vendor, printers = printers) }
        if (saved.printer !in printers) return@loadPresets
        val compatible = presets.compatiblePresets(saved.vendor, saved.printer)
            .getOrElse { return@loadPresets showPresetError(it) }
        val filament = saved.filaments.firstOrNull()
        updateSetup {
            copy(
                printer = saved.printer,
                processes = compatible.processes,
                process = saved.process.takeIf { it in compatible.processes } ?: compatible.defaultProcess,
                filaments = compatible.filaments,
                filament = filament?.takeIf { it in compatible.filaments } ?: compatible.defaultFilament,
            )
        }
    }

    private fun rememberSelection() {
        val selection = _state.value.printerSetup.selection ?: return
        viewModelScope.launch { settings.savePresetSelection(selection) }
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
            rememberSelection()
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

    private fun EngineStatus.toUiState(): EngineUiState = when (this) {
        is EngineStatus.Available -> EngineUiState.Ready(info)
        is EngineStatus.Unavailable -> EngineUiState.Unavailable(reason)
    }
}
