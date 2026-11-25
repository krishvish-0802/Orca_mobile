package com.orcamobile.core.domain.presets

import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.UserPreset

/** Where the user's presets are kept. */
interface UserPresetRepository {
    suspend fun all(): List<UserPreset>

    /** Saves [preset], replacing one of its kind and name. */
    suspend fun save(preset: UserPreset)

    suspend fun delete(kind: PresetKind, name: String)
}

/** User presets kept in memory only (for tests, and screens without storage). */
class InMemoryUserPresets(initial: List<UserPreset> = emptyList()) : UserPresetRepository {
    private val presets = initial.toMutableList()

    override suspend fun all(): List<UserPreset> = presets.toList()

    override suspend fun save(preset: UserPreset) {
        presets.removeAll { it.kind == preset.kind && it.name == preset.name }
        presets += preset
    }

    override suspend fun delete(kind: PresetKind, name: String) {
        presets.removeAll { it.kind == kind && it.name == name }
    }
}
