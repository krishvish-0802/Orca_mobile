package com.orcamobile.core.domain.usecase

import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.domain.model.SampleModelRepository
import com.orcamobile.core.domain.model.WorkspaceRepository
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import javax.inject.Inject

/**
 * Slices the bundled calibration cube with engine defaults. This is the
 * end-to-end smoke test of the on-device engine (milestone M0).
 */
class SliceSampleModelUseCase @Inject constructor(
    private val engine: SlicingEngine,
    private val samples: SampleModelRepository,
    private val workspace: WorkspaceRepository,
) {
    operator fun invoke(overrides: Map<String, String> = DEFAULT_OVERRIDES): Flow<SliceEvent> = flow {
        val request = SliceRequest(
            modelPath = samples.calibrationCubePath(),
            outputGcodePath = workspace.newGcodePath("calibration_cube"),
            configOverrides = overrides,
        )
        emitAll(engine.slice(request))
    }

    companion object {
        /** A generic 220 mm bed with a 0.4 mm nozzle and PLA-like temperatures. */
        val DEFAULT_OVERRIDES: Map<String, String> = mapOf(
            "printable_area" to "0x0,220x0,220x220,0x220",
            "printable_height" to "250",
            "layer_height" to "0.2",
            "initial_layer_print_height" to "0.2",
        )
    }
}
