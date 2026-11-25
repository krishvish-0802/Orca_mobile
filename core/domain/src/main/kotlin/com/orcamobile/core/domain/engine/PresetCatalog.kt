package com.orcamobile.core.domain.engine

import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.CompatiblePresets
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.UserPreset

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

    /**
     * The user's presets, replacing those given before: a selection may name
     * them, and the lists show them wherever the preset they inherit is listed.
     */
    suspend fun setUserPresets(presets: List<UserPreset>): Result<Unit> = Result.success(Unit)

    /** Each filament's colour as [selection] prints it ("#RRGGBB"): its own colour, or its preset's. */
    suspend fun filamentColours(selection: PresetSelection): Result<List<String>> =
        Result.success(selection.filamentColours)
}
