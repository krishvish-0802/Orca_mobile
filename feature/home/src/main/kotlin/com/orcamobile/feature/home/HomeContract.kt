package com.orcamobile.feature.home

import com.orcamobile.core.model.EngineInfo
import com.orcamobile.core.model.SliceStatistics

/** Everything the home screen renders. Produced only by [HomeViewModel]. */
data class HomeUiState(
    val engine: EngineUiState = EngineUiState.Loading,
    val slice: SliceUiState = SliceUiState.Idle,
)

sealed interface EngineUiState {
    data object Loading : EngineUiState

    data class Ready(val info: EngineInfo) : EngineUiState

    data class Unavailable(val reason: String) : EngineUiState
}

sealed interface SliceUiState {
    data object Idle : SliceUiState

    data class Running(val percent: Int, val message: String) : SliceUiState

    data class Done(val statistics: SliceStatistics, val gcodePath: String) : SliceUiState

    data object Canceled : SliceUiState

    data class Failed(val message: String) : SliceUiState
}

/** User actions on the home screen. */
sealed interface HomeIntent {
    data object SliceSample : HomeIntent

    data object CancelSlice : HomeIntent
}
