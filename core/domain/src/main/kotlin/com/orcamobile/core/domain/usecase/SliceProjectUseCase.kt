package com.orcamobile.core.domain.usecase

import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.domain.model.WorkspaceRepository
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.ProjectSummary
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceRequest
import com.orcamobile.core.model.SliceSource
import com.orcamobile.core.domain.settings.PrintSettingsRepository
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
    private val settings: PrintSettingsRepository,
) {
    operator fun invoke(project: ProjectSummary, presets: PresetSelection?): Flow<SliceEvent> = flow {
        val base = if (presets == null) DEFAULT_OVERRIDES else emptyMap()
        val request = SliceRequest(
            source = SliceSource.Project(project.id),
            outputGcodePath = workspace.newGcodePath(File(project.sourcePath).nameWithoutExtension),
            presets = presets,
            configOverrides = base + settings.editsFor(presets),
        )
        emitAll(engine.slice(request))
    }

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
