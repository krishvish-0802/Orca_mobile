package com.orcamobile.core.domain.engine

import com.orcamobile.core.model.CompatiblePresets

/**
 * OrcaSlicer's bundled system profiles. Lookups load a vendor's profiles on
 * first use, so they are suspending; failures (unknown vendor, engine not
 * available) come back as a failed [Result].
 */
interface PresetCatalog {
    suspend fun vendors(): Result<List<String>>

    suspend fun printers(vendor: String): Result<List<String>>

    suspend fun compatiblePresets(vendor: String, printer: String): Result<CompatiblePresets>
}
