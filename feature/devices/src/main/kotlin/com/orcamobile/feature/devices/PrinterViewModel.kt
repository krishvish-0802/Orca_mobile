package com.orcamobile.feature.devices

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.printer.CameraFrames
import com.orcamobile.core.domain.printer.PrinterConnection
import com.orcamobile.core.domain.printer.PrinterRepository
import com.orcamobile.core.domain.printer.PrinterSessions
import com.orcamobile.core.model.FanKind
import com.orcamobile.core.model.HeaterKind
import com.orcamobile.core.model.PrintRecord
import com.orcamobile.core.model.PrinterFile
import com.orcamobile.core.model.PrinterState
import com.orcamobile.core.model.SavedPrinter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the printer's protocol lets the screen do. */
data class PrinterAbilities(
    val jobs: Boolean = false,
    val heaters: Set<HeaterKind> = emptySet(),
    val fans: Set<FanKind> = emptySet(),
    val speed: IntRange? = null,
    val home: Boolean = false,
    val light: Boolean = false,
    val camera: Boolean = false,
    val files: Boolean = false,
    val history: Boolean = false,
)

sealed interface CameraUi {
    data object Off : CameraUi

    data object Starting : CameraUi

    data class Showing(val frame: ImageBitmap?) : CameraUi

    data class Failed(val message: String) : CameraUi
}

sealed interface ListUi<out T> {
    data object NotLoaded : ListUi<Nothing>

    data object Loading : ListUi<Nothing>

    data class Loaded<T>(val items: List<T>) : ListUi<T>

    data class Failed(val message: String) : ListUi<Nothing>
}

data class PrinterUiState(
    val printer: SavedPrinter? = null,
    /** Null where the protocol only takes uploads. */
    val status: PrinterState? = null,
    val abilities: PrinterAbilities = PrinterAbilities(),
    val camera: CameraUi = CameraUi.Off,
    val files: ListUi<PrinterFile> = ListUi.NotLoaded,
    val history: ListUi<PrintRecord> = ListUi.NotLoaded,
    /** What a command the printer refused said, until dismissed. */
    val problem: String? = null,
)

sealed interface PrinterIntent {
    data object Pause : PrinterIntent
    data object Resume : PrinterIntent
    data object Stop : PrinterIntent
    data class SetTemperature(val heater: HeaterKind, val celsius: Int) : PrinterIntent
    data class SetFan(val fan: FanKind, val percent: Int) : PrinterIntent
    data class SetSpeed(val percent: Int) : PrinterIntent
    data class SetLight(val on: Boolean) : PrinterIntent
    data object Home : PrinterIntent
    data object ToggleCamera : PrinterIntent
    data object LoadFiles : PrinterIntent
    data class PrintFile(val file: PrinterFile) : PrinterIntent
    data class DeleteFile(val file: PrinterFile) : PrinterIntent
    data object LoadHistory : PrinterIntent
    data object DismissProblem : PrinterIntent
}

/** One printer: its live status, and the controls its protocol offers. */
@HiltViewModel
class PrinterViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: PrinterRepository,
    private val sessions: PrinterSessions,
    private val cameraFrames: CameraFrames,
) : ViewModel() {
    private val printerId: String = checkNotNull(savedStateHandle.get<String>(DEVICES_PRINTER_ARG)) { "No printer to show" }
    private val _state = MutableStateFlow(PrinterUiState())
    val state: StateFlow<PrinterUiState> = _state.asStateFlow()

    private var connection: PrinterConnection? = null
    private var statusJob: Job? = null
    private var cameraJob: Job? = null

    init {
        viewModelScope.launch {
            repository.printers.map { list -> list.firstOrNull { it.id == printerId } }.filterNotNull().collect { printer -> follow(printer) }
        }
    }

    /** Follows [printer]'s connection (a new one when its address changed). */
    private fun follow(printer: SavedPrinter) {
        val next = sessions.connection(printer)
        _state.update { it.copy(printer = printer, abilities = abilitiesOf(next)) }
        if (next === connection) return
        connection = next
        statusJob?.cancel()
        stopCamera()
        statusJob = next?.let { live -> viewModelScope.launch { live.state.collect { status -> _state.update { it.copy(status = status) } } } }
        if (next == null) _state.update { it.copy(status = null) }
    }

    fun onIntent(intent: PrinterIntent) {
        val live = connection ?: return
        when (intent) {
            PrinterIntent.Pause -> act { live.jobs?.pause() }
            PrinterIntent.Resume -> act { live.jobs?.resume() }
            PrinterIntent.Stop -> act { live.jobs?.stop() }
            is PrinterIntent.SetTemperature -> act { live.temperatures?.setTarget(intent.heater, intent.celsius) }
            is PrinterIntent.SetFan -> act { live.fans?.setFan(intent.fan, intent.percent) }
            is PrinterIntent.SetSpeed -> act { live.fans?.setSpeed(intent.percent) }
            is PrinterIntent.SetLight -> act { live.light?.setLight(intent.on) }
            PrinterIntent.Home -> act { live.motion?.home() }
            PrinterIntent.ToggleCamera -> if (_state.value.camera == CameraUi.Off || _state.value.camera is CameraUi.Failed) startCamera(live) else stopCamera()
            PrinterIntent.LoadFiles -> loadFiles(live)
            is PrinterIntent.PrintFile -> act { live.files?.print(intent.file) }
            is PrinterIntent.DeleteFile -> viewModelScope.launch {
                live.files?.delete(listOf(intent.file))?.onFailure { e -> showProblem(e) }
                loadFiles(live)
            }
            PrinterIntent.LoadHistory -> viewModelScope.launch {
                val history = live.history ?: return@launch
                _state.update { it.copy(history = ListUi.Loading) }
                val result = history.recent()
                _state.update { it.copy(history = result.fold({ list -> ListUi.Loaded(list) }, { e -> ListUi.Failed(message(e)) })) }
            }
            PrinterIntent.DismissProblem -> _state.update { it.copy(problem = null) }
        }
    }

    private fun act(command: suspend () -> Result<Unit>?) {
        viewModelScope.launch { command()?.onFailure(::showProblem) }
    }

    private fun showProblem(error: Throwable) = _state.update { it.copy(problem = message(error)) }

    private fun message(error: Throwable) = error.message ?: error.javaClass.simpleName

    private fun loadFiles(live: PrinterConnection) {
        val files = live.files ?: return
        _state.update { it.copy(files = ListUi.Loading) }
        viewModelScope.launch {
            val result = files.list()
            _state.update { it.copy(files = result.fold({ list -> ListUi.Loaded(list.sortedBy { f -> f.name.lowercase() }) }, { e -> ListUi.Failed(message(e)) })) }
        }
    }

    private fun startCamera(live: PrinterConnection) {
        val camera = live.camera ?: return
        _state.update { it.copy(camera = CameraUi.Starting) }
        cameraJob = viewModelScope.launch {
            val feed = camera.open().getOrElse { e ->
                _state.update { it.copy(camera = CameraUi.Failed(message(e))) }
                return@launch
            }
            _state.update { it.copy(camera = CameraUi.Showing(null)) }
            runCatching {
                cameraFrames.frames(feed)
                    .conflate()
                    .map { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                    .flowOn(Dispatchers.Default)
                    .collect { frame -> if (frame != null) _state.update { it.copy(camera = CameraUi.Showing(frame)) } }
            }.onFailure { e ->
                if (e !is CancellationException) _state.update { it.copy(camera = CameraUi.Failed(message(e))) }
            }
        }
    }

    private fun stopCamera() {
        val wasOn = cameraJob != null
        cameraJob?.cancel()
        cameraJob = null
        _state.update { it.copy(camera = CameraUi.Off) }
        val live = connection
        if (wasOn && live != null) viewModelScope.launch { withContext(NonCancellable) { live.camera?.release() } }
    }

    override fun onCleared() {
        // The connection stays open for the list and notifications; only the camera stops.
        val wasOn = cameraJob != null
        cameraJob?.cancel()
        val live = connection ?: return
        // The screen's scope is gone: a short-lived one tells the printer it can stop streaming.
        if (wasOn) CoroutineScope(Dispatchers.IO).launch { live.camera?.release() }
    }

    private fun abilitiesOf(live: PrinterConnection?) = if (live == null) {
        PrinterAbilities()
    } else {
        PrinterAbilities(
            jobs = live.jobs != null,
            heaters = live.temperatures?.heaters.orEmpty(),
            fans = live.fans?.fans.orEmpty(),
            speed = live.fans?.speedRange,
            home = live.motion != null,
            light = live.light != null,
            camera = live.camera != null,
            files = live.files != null,
            history = live.history != null,
        )
    }

    /** Waits until the printer is known (for tests). */
    internal suspend fun awaitPrinter() = state.first { it.printer != null }
}
