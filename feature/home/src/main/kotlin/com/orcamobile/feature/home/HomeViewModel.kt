package com.orcamobile.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.usecase.GetEngineStatusUseCase
import com.orcamobile.core.domain.usecase.SliceSampleModelUseCase
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
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var sliceJob: Job? = null

    init {
        _state.update { it.copy(engine = getEngineStatus().toUiState()) }
    }

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            HomeIntent.SliceSample -> startSlice()
            HomeIntent.CancelSlice -> cancelSlice()
        }
    }

    private fun startSlice() {
        if (sliceJob?.isActive == true) return
        _state.update { it.copy(slice = SliceUiState.Running(0, "Preparing")) }
        sliceJob = viewModelScope.launch {
            sliceSampleModel().collect { event ->
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
