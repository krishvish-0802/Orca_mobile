package com.orcamobile.core.domain.usecase

import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.domain.model.WorkspaceRepository
import com.orcamobile.core.domain.project.PlateConfigResolver
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.ProjectSummary
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceRequest
import com.orcamobile.core.model.SliceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.File
import javax.inject.Inject

/**
 * Slices a loaded project's current plate as placed, with the chosen presets,
 * the user's settings changes over them and the plate's own settings over
 * those (the engine adds the last).
 */
class SliceProjectUseCase @Inject constructor(
    private val engine: SlicingEngine,
    private val workspace: WorkspaceRepository,
    private val configs: PlateConfigResolver,
) {
    /** [plate], when given, names the G-code after the plate too ("benchy_plate2"), 0-based. */
    operator fun invoke(project: ProjectSummary, presets: PresetSelection?, plate: Int? = null): Flow<SliceEvent> = flow {
        val config = configs.configFor(presets)
        val model = File(project.sourcePath).nameWithoutExtension
        val request = SliceRequest(
            source = SliceSource.Project(project.id),
            outputGcodePath = workspace.newGcodePath(if (plate == null) model else "${model}_plate${plate + 1}"),
            presets = config.presets,
            configOverrides = config.overrides,
        )
        emitAll(engine.slice(request))
    }
}
