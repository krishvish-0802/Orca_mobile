package com.orcamobile.core.domain.usecase

import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.model.EngineStatus
import javax.inject.Inject

class GetEngineStatusUseCase @Inject constructor(
    private val engine: SlicingEngine,
) {
    operator fun invoke(): EngineStatus = engine.status()
}
