package com.orcamobile.core.domain.settings

import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.SettingsEdits
import kotlinx.coroutines.flow.Flow

/**
 * The user's changes to the chosen presets' settings, as the desktop app keeps
 * a modified preset before it is saved. Changes belong to one preset selection:
 * after choosing other presets they no longer apply.
 */
interface PrintSettingsRepository {
    /** The changes, with the selection they were made for. */
    val edits: Flow<SettingsEdits>

    /** The changes that apply to [selection] (empty for another selection). */
    suspend fun editsFor(selection: PresetSelection?): Map<String, String>

    suspend fun set(selection: PresetSelection?, key: String, value: String)

    suspend fun reset(selection: PresetSelection?, key: String)

    suspend fun resetAll()

    /** [values] become the changes, made for [selection] (other changes go). */
    suspend fun replace(selection: PresetSelection?, values: Map<String, String>) {
        resetAll()
        for ((key, value) in values) set(selection, key, value)
    }
}
