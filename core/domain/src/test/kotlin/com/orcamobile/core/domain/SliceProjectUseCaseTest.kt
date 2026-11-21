package com.orcamobile.core.domain

import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.domain.model.WorkspaceRepository
import com.orcamobile.core.domain.usecase.SliceProjectUseCase
import com.orcamobile.core.model.EngineStatus
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.ProjectSummary
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceRequest
import com.orcamobile.core.model.SliceResult
import com.orcamobile.core.model.SliceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class SliceProjectUseCaseTest {

    private class RecordingEngine : SlicingEngine {
        var lastRequest: SliceRequest? = null
        override fun status(): EngineStatus = EngineStatus.Unavailable("fake")
        override fun slice(request: SliceRequest): Flow<SliceEvent> {
            lastRequest = request
            return flowOf(SliceEvent.Finished(SliceResult.Canceled))
        }
    }

    private val workspace = object : WorkspaceRepository {
        override fun newGcodePath(baseName: String) = "/out/$baseName.gcode"
    }

    private val project = ProjectSummary(ProjectId("p1"), "/models/3DBenchy.stl", emptyList())

    @Test
    fun `slices the project with the chosen presets into a G-code named after the model`() = runTest {
        val engine = RecordingEngine()
        val presets = PresetSelection("Creality", "Ender-3", "0.20mm Standard", listOf("PLA"))

        val events = SliceProjectUseCase(engine, workspace)(project, presets).toList()

        assertEquals(listOf<SliceEvent>(SliceEvent.Finished(SliceResult.Canceled)), events)
        val request = requireNotNull(engine.lastRequest)
        assertEquals(SliceSource.Project(ProjectId("p1")), request.source)
        assertEquals("/out/3DBenchy.gcode", request.outputGcodePath)
        assertEquals(presets, request.presets)
        assertEquals(emptyMap(), request.configOverrides)
    }

    @Test
    fun `without presets the generic bed overrides apply`() = runTest {
        val engine = RecordingEngine()
        SliceProjectUseCase(engine, workspace)(project, null).toList()
        assertEquals(SliceProjectUseCase.DEFAULT_OVERRIDES, engine.lastRequest?.configOverrides)
    }
}
