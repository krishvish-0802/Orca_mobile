package com.orcamobile.core.domain.usecase

import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.domain.model.WorkspaceRepository
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.ProjectSummary
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceRequest
import com.orcamobile.core.model.SliceSource
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject

/** Slices a loaded project as placed on the plate, with the chosen presets. */
class SliceProjectUseCase @Inject constructor(
    private val engine: SlicingEngine,
    private val workspace: WorkspaceRepository,
) {
    operator fun invoke(project: ProjectSummary, presets: PresetSelection?): Flow<SliceEvent> =
        engine.slice(
            SliceRequest(
                source = SliceSource.Project(project.id),
                outputGcodePath = workspace.newGcodePath(File(project.sourcePath).nameWithoutExtension),
                presets = presets,
                configOverrides = if (presets == null) DEFAULT_OVERRIDES else emptyMap(),
            ),
        )

    companion object {
        /** Without presets: a generic 220 mm bed, 0.2 mm layers, over the engine defaults. */
        val DEFAULT_OVERRIDES: Map<String, String> = mapOf(
            "printable_area" to "0x0,220x0,220x220,0x220",
            "printable_height" to "250",
            "layer_height" to "0.2",
            "initial_layer_print_height" to "0.2",
        )
    }
}
