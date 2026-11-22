package com.orcamobile.core.domain.project

import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.model.PlateConfig
import com.orcamobile.core.model.PresetSelection
import javax.inject.Inject

/**
 * The config a plate is sliced and arranged with: the chosen presets with the
 * user's settings changes over them, or a generic printer without presets.
 */
class PlateConfigResolver @Inject constructor(
    private val settings: PrintSettingsRepository,
) {
    suspend fun configFor(presets: PresetSelection?): PlateConfig {
        val base = if (presets == null) DEFAULT_OVERRIDES else emptyMap()
        return PlateConfig(presets, base + settings.editsFor(presets))
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
