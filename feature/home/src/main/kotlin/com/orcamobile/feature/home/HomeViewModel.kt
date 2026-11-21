package com.orcamobile.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.model.ModelImportRepository
import com.orcamobile.core.domain.model.SampleModelRepository
import com.orcamobile.core.domain.presets.PresetPicker
import com.orcamobile.core.domain.usecase.GetEngineStatusUseCase
import com.orcamobile.core.model.EngineStatus
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val presets: PresetPicker,
    private val imports: ModelImportRepository,
    private val samples: SampleModelRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    init {
        val engine = getEngineStatus().toUiState()
        _state.update { it.copy(engine = engine) }
        if (engine is EngineUiState.Ready) {
            viewModelScope.launch { presets.load() }
            viewModelScope.launch { presets.choices.collect { choices -> _state.update { it.copy(printerSetup = choices) } } }
        }
    }

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            is HomeIntent.SelectVendor -> viewModelScope.launch { presets.selectVendor(intent.vendor) }
            is HomeIntent.SelectPrinter -> viewModelScope.launch { presets.selectPrinter(intent.printer) }
            is HomeIntent.SelectProcess -> viewModelScope.launch { presets.selectProcess(intent.process) }
            is HomeIntent.SelectFilament -> viewModelScope.launch { presets.selectFilament(intent.filament) }
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

    private fun EngineStatus.toUiState(): EngineUiState = when (this) {
        is EngineStatus.Available -> EngineUiState.Ready(info)
        is EngineStatus.Unavailable -> EngineUiState.Unavailable(reason)
    }
}
