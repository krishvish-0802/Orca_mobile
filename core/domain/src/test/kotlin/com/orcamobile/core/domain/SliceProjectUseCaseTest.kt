package com.orcamobile.core.domain

import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.domain.model.WorkspaceRepository
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.domain.project.PlateConfigResolver
import com.orcamobile.core.domain.usecase.SliceProjectUseCase
import com.orcamobile.core.model.EngineStatus
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.ProjectSummary
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceRequest
import com.orcamobile.core.model.SliceResult
import com.orcamobile.core.model.SettingsEdits
import com.orcamobile.core.model.SliceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

        val events = SliceProjectUseCase(engine, workspace, PlateConfigResolver(FakePrintSettings()))(project, presets).toList()

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
        SliceProjectUseCase(engine, workspace, PlateConfigResolver(FakePrintSettings()))(project, null).toList()
        assertEquals(PlateConfigResolver.DEFAULT_OVERRIDES, engine.lastRequest?.configOverrides)
    }

    @Test
    fun `the user's settings changes go over the presets`() = runTest {
        val engine = RecordingEngine()
        val presets = PresetSelection("Elegoo", "Elegoo Centauri Carbon 0.4 nozzle", "0.20mm Standard", listOf("PLA"))
        val settings = FakePrintSettings(SettingsEdits(presets, mapOf("sparse_infill_density" to "25%")))

        SliceProjectUseCase(engine, workspace, PlateConfigResolver(settings))(project, presets).toList()
        assertEquals(mapOf("sparse_infill_density" to "25%"), engine.lastRequest?.configOverrides)

        // Changes made for other presets do not apply.
        SliceProjectUseCase(engine, workspace, PlateConfigResolver(settings))(project, presets.copy(process = "0.12mm Fine")).toList()
        assertEquals(emptyMap(), engine.lastRequest?.configOverrides)
    }
}

/** In-memory [PrintSettingsRepository] for tests. */
class FakePrintSettings(initial: SettingsEdits = SettingsEdits.NONE) : PrintSettingsRepository {
    private val state = MutableStateFlow(initial)
    override val edits: Flow<SettingsEdits> = state
    override suspend fun editsFor(selection: PresetSelection?) = state.value.takeIf { it.appliesTo(selection) }?.values.orEmpty()
    override suspend fun set(selection: PresetSelection?, key: String, value: String) {
        state.value = SettingsEdits(selection, editsFor(selection) + (key to value))
    }
    override suspend fun reset(selection: PresetSelection?, key: String) {
        state.value = SettingsEdits(selection, editsFor(selection) - key)
    }
    override suspend fun resetAll() {
        state.value = SettingsEdits.NONE
    }
}
