package com.orcamobile.core.domain.engine

import com.orcamobile.core.model.EngineStatus
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceRequest
import kotlinx.coroutines.flow.Flow

/**
 * The slicing engine as the rest of the app sees it. The native OrcaSlicer
 * engine implements it in `:core:engine`; tests use fakes.
 */
interface SlicingEngine {
    fun status(): EngineStatus

    /**
     * Slices [request]. The flow emits progress and ends with one
     * [SliceEvent.Finished]. Cancelling the collector cancels the slice.
     */
    fun slice(request: SliceRequest): Flow<SliceEvent>
}
