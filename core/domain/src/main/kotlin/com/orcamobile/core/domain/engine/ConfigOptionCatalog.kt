package com.orcamobile.core.domain.engine

import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.PresetOptionKeys
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.RulesScope
import com.orcamobile.core.model.SettingsReview

/** OrcaSlicer's option definitions and the values presets give them. */
interface ConfigOptionCatalog {
    /** Definitions of [keys], in that order; unknown keys are left out. */
    suspend fun describe(keys: List<String>): Result<List<ConfigOption>>

    /**
     * Values of [keys] in the config a slice with [selection] starts from
     * (the engine defaults when null), serialized as in profiles.
     */
    suspend fun values(selection: PresetSelection?, keys: List<String>): Result<Map<String, String>>

    /** Which options each kind of preset holds (the full set the settings screen shows). */
    suspend fun presetOptionKeys(): Result<PresetOptionKeys>

    /**
     * The desktop's rules between settings, for [selection]'s presets (or the
     * engine defaults) with [settings] over them, edited at [scope]; [filament]
     * is the filament slot whose settings are shown.
     */
    suspend fun review(
        selection: PresetSelection?,
        settings: Map<String, String>,
        scope: RulesScope,
        filament: Int = 0,
    ): Result<SettingsReview> = Result.success(SettingsReview.NONE)
}
