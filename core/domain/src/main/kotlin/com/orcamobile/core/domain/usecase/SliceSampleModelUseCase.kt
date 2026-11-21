package com.orcamobile.core.domain.usecase

import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.domain.model.SampleModelRepository
import com.orcamobile.core.domain.model.WorkspaceRepository
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import javax.inject.Inject

/**
 * Slices the bundled calibration cube: with the given system presets, or with
 * the engine defaults on a generic 220 mm bed when none are chosen. This is the
 * end-to-end check of the on-device engine.
 */
class SliceSampleModelUseCase @Inject constructor(
    private val engine: SlicingEngine,
    private val samples: SampleModelRepository,
    private val workspace: WorkspaceRepository,
) {
    operator fun invoke(presets: PresetSelection? = null): Flow<SliceEvent> = flow {
        val request = SliceRequest(
            modelPath = samples.calibrationCubePath(),
            outputGcodePath = workspace.newGcodePath("calibration_cube"),
            presets = presets,
            configOverrides = if (presets == null) DEFAULT_OVERRIDES else emptyMap(),
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
