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
 * Slices a loaded project as placed on the plate, with the chosen presets and
 * the user's settings changes over them.
 */
class SliceProjectUseCase @Inject constructor(
    private val engine: SlicingEngine,
    private val workspace: WorkspaceRepository,
    private val configs: PlateConfigResolver,
) {
    operator fun invoke(project: ProjectSummary, presets: PresetSelection?): Flow<SliceEvent> = flow {
        val config = configs.configFor(presets)
        val request = SliceRequest(
            source = SliceSource.Project(project.id),
            outputGcodePath = workspace.newGcodePath(File(project.sourcePath).nameWithoutExtension),
            presets = config.presets,
            configOverrides = config.overrides,
        )
        emitAll(engine.slice(request))
    }
}
