package com.orcamobile.feature.home

import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.domain.model.SampleModelRepository
import com.orcamobile.core.domain.model.WorkspaceRepository
import com.orcamobile.core.domain.usecase.GetEngineStatusUseCase
import com.orcamobile.core.domain.usecase.SliceSampleModelUseCase
import com.orcamobile.core.model.EngineInfo
import com.orcamobile.core.model.EngineStatus
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceRequest
import com.orcamobile.core.model.SliceResult
import com.orcamobile.core.model.SliceStatistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val info = EngineInfo("OrcaSlicer", "2.6.0-dev", "02.08.01.55", "abc")

    /** An engine whose slice events the test pushes by hand. */
    private class ScriptedEngine(private val status: EngineStatus) : SlicingEngine {
        val events = MutableSharedFlow<SliceEvent>()
        override fun status() = status
        override fun slice(request: SliceRequest): Flow<SliceEvent> = events
    }

    private val samples = object : SampleModelRepository {
        override suspend fun calibrationCubePath() = "/cube.stl"
    }
    private val workspace = object : WorkspaceRepository {
        override fun newGcodePath(baseName: String) = "/$baseName.gcode"
    }

    private fun viewModel(engine: SlicingEngine) =
        HomeViewModel(GetEngineStatusUseCase(engine), SliceSampleModelUseCase(engine, samples, workspace))

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `reports an unavailable engine`() {
        val vm = viewModel(ScriptedEngine(EngineStatus.Unavailable("not bundled")))
        assertEquals(EngineUiState.Unavailable("not bundled"), vm.state.value.engine)
    }

    @Test
    fun `follows a slice from progress to result`() = runTest {
        val engine = ScriptedEngine(EngineStatus.Available(info))
        val vm = viewModel(engine)
        assertEquals(EngineUiState.Ready(info), vm.state.value.engine)

        vm.onIntent(HomeIntent.SliceSample)
        engine.events.emit(SliceEvent.Progress(40, "Generating walls"))
        assertEquals(SliceUiState.Running(40, "Generating walls"), vm.state.value.slice)

        val stats = SliceStatistics(60.0, 1000.0, 3.0, 100, 2048)
        engine.events.emit(SliceEvent.Finished(SliceResult.Succeeded("/calibration_cube.gcode", stats)))
        assertEquals(SliceUiState.Done(stats, "/calibration_cube.gcode"), vm.state.value.slice)
    }

    @Test
    fun `cancel stops a running slice`() = runTest {
        val engine = ScriptedEngine(EngineStatus.Available(info))
        val vm = viewModel(engine)

        vm.onIntent(HomeIntent.SliceSample)
        assertTrue(vm.state.value.slice is SliceUiState.Running)
        vm.onIntent(HomeIntent.CancelSlice)
        assertEquals(SliceUiState.Canceled, vm.state.value.slice)

        // Late events from the cancelled slice no longer reach the state.
        engine.events.emit(SliceEvent.Progress(90, "late"))
        assertEquals(SliceUiState.Canceled, vm.state.value.slice)
    }
}
