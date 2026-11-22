package com.orcamobile.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.model.ModelImportRepository
import com.orcamobile.core.domain.model.SampleModelRepository
import com.orcamobile.core.domain.presets.PresetPicker
import com.orcamobile.core.domain.update.AppUpdates
import com.orcamobile.core.domain.usecase.GetEngineStatusUseCase
import com.orcamobile.core.model.AppUpdate
import com.orcamobile.core.model.EngineStatus
import com.orcamobile.core.model.InstallStart
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getEngineStatus: GetEngineStatusUseCase,
    private val presets: PresetPicker,
    private val imports: ModelImportRepository,
    private val samples: SampleModelRepository,
    private val updates: AppUpdates,
) : ViewModel() {

    private var lastCheck = 0L
    private var dismissedVersion = 0L
    private var downloadJob: Job? = null

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
            HomeIntent.CheckForUpdate -> checkForUpdate()
            HomeIntent.DownloadUpdate -> downloadUpdate()
            HomeIntent.InstallUpdate -> installUpdate()
            HomeIntent.DismissUpdate -> {
                (_state.value.update.update())?.let { dismissedVersion = it.versionCode }
                _state.update { it.copy(update = UpdateUiState.None) }
            }
        }
    }

    // --- updates ------------------------------------------------------------

    /** At most every few minutes; a found update stays until it is installed or dismissed. */
    private fun checkForUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastCheck < CHECK_INTERVAL_MS || _state.value.update !is UpdateUiState.None) return
        lastCheck = now
        viewModelScope.launch {
            // Offline or GitHub unreachable: nothing to show, try again later.
            val update = updates.check().getOrNull() ?: return@launch
            if (update.versionCode <= dismissedVersion) return@launch
            _state.update { if (it.update is UpdateUiState.None) it.copy(update = UpdateUiState.Available(update)) else it }
        }
    }

    private fun downloadUpdate() {
        val update = _state.value.update.update() ?: return
        if (downloadJob?.isActive == true) return
        _state.update { it.copy(update = UpdateUiState.Downloading(update, null)) }
        downloadJob = viewModelScope.launch {
            updates.download(update) { read, total ->
                val percent = if (total > 0) (read * 100 / total).toInt() else null
                _state.update { state ->
                    val current = state.update
                    if (current is UpdateUiState.Downloading && current.percent != percent) {
                        state.copy(update = current.copy(percent = percent))
                    } else {
                        state
                    }
                }
            }.fold(
                { path ->
                    _state.update { it.copy(update = UpdateUiState.Ready(update, path)) }
                    installUpdate()
                },
                { error -> _state.update { it.copy(update = UpdateUiState.Failed(update, error.message ?: "Download failed")) } },
            )
        }
    }

    private fun installUpdate() {
        val ready = _state.value.update as? UpdateUiState.Ready ?: return
        updates.install(ready.apkPath).fold(
            { start -> _state.update { it.copy(update = ready.copy(needsPermission = start == InstallStart.NeedsPermission)) } },
            { error -> _state.update { it.copy(update = UpdateUiState.Failed(ready.update, error.message ?: "Install failed")) } },
        )
    }

    private fun UpdateUiState.update(): AppUpdate? = when (this) {
        UpdateUiState.None -> null
        is UpdateUiState.Available -> update
        is UpdateUiState.Downloading -> update
        is UpdateUiState.Ready -> update
        is UpdateUiState.Failed -> update
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

    private companion object {
        const val CHECK_INTERVAL_MS = 5 * 60 * 1000L
    }

    private fun EngineStatus.toUiState(): EngineUiState = when (this) {
        is EngineStatus.Available -> EngineUiState.Ready(info)
        is EngineStatus.Unavailable -> EngineUiState.Unavailable(reason)
    }
}
