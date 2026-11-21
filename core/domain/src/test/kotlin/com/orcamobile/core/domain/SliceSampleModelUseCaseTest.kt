package com.orcamobile.core.domain

import app.cash.turbine.test
import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.domain.model.SampleModelRepository
import com.orcamobile.core.domain.model.WorkspaceRepository
import com.orcamobile.core.domain.usecase.SliceSampleModelUseCase
import com.orcamobile.core.model.EngineStatus
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceRequest
import com.orcamobile.core.model.SliceResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class SliceSampleModelUseCaseTest {

    private class RecordingEngine : SlicingEngine {
        var lastRequest: SliceRequest? = null

        override fun status(): EngineStatus = EngineStatus.Unavailable("fake")

        override fun slice(request: SliceRequest): Flow<SliceEvent> {
            lastRequest = request
            return flowOf(
                SliceEvent.Progress(50, "Slicing"),
                SliceEvent.Finished(SliceResult.Canceled),
            )
        }
    }

    private val samples = object : SampleModelRepository {
        override suspend fun calibrationCubePath() = "/samples/cube.stl"
    }

    private val workspace = object : WorkspaceRepository {
        override fun newGcodePath(baseName: String) = "/out/$baseName.gcode"
    }

    @Test
    fun `slices the calibration cube into the workspace with default overrides`() = runTest {
        val engine = RecordingEngine()
        val useCase = SliceSampleModelUseCase(engine, samples, workspace)

        useCase().test {
            assertEquals(SliceEvent.Progress(50, "Slicing"), awaitItem())
            assertEquals(SliceEvent.Finished(SliceResult.Canceled), awaitItem())
            awaitComplete()
        }

        val request = requireNotNull(engine.lastRequest)
        assertEquals("/samples/cube.stl", request.modelPath)
        assertEquals("/out/calibration_cube.gcode", request.outputGcodePath)
        assertEquals(SliceSampleModelUseCase.DEFAULT_OVERRIDES, request.configOverrides)
        assertEquals(null, request.presets)
    }

    @Test
    fun `slices with the chosen presets and no generic overrides`() = runTest {
        val engine = RecordingEngine()
        val presets = PresetSelection("Creality", "Creality K1 (0.4 nozzle)", "0.20mm Standard @Creality K1 (0.4 nozzle)", listOf("Creality Generic PLA"))

        SliceSampleModelUseCase(engine, samples, workspace)(presets).test {
            awaitItem()
            awaitItem()
            awaitComplete()
        }

        val request = requireNotNull(engine.lastRequest)
        assertEquals(presets, request.presets)
        assertEquals(emptyMap(), request.configOverrides)
    }
}
