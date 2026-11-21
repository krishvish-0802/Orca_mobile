package com.orcamobile.core.domain.preview

import com.orcamobile.core.model.GCodePreviewSummary
import com.orcamobile.core.model.PreviewId

/** G-code files read into the engine for the preview. A preview holds memory until [release]. */
interface GCodePreviewRepository {
    suspend fun load(gcodePath: String): Result<GCodePreviewSummary>

    fun release(id: PreviewId)
}
