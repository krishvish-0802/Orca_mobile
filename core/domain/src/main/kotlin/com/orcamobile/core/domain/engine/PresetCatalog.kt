package com.orcamobile.core.domain.engine

import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.CompatiblePresets
import com.orcamobile.core.model.PresetSelection

/**
 * OrcaSlicer's bundled system profiles. Lookups load a vendor's profiles on
 * first use, so they are suspending; failures (unknown vendor, engine not
 * available) come back as a failed [Result].
 */
interface PresetCatalog {
    suspend fun vendors(): Result<List<String>>

    suspend fun printers(vendor: String): Result<List<String>>

    suspend fun compatiblePresets(vendor: String, printer: String): Result<CompatiblePresets>

    suspend fun bedShape(vendor: String, printer: String): Result<BedShape>

    /** Each filament's colour as [selection] prints it ("#RRGGBB"): its own colour, or its preset's. */
    suspend fun filamentColours(selection: PresetSelection): Result<List<String>> =
        Result.success(selection.filamentColours)
}
