package com.orcamobile.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.SettingsEdits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps the settings changes in the app's Preferences DataStore, as one JSON entry. */
@Singleton
class DataStorePrintSettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) : PrintSettingsRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override val edits: Flow<SettingsEdits> = store.data.map { prefs -> decode(prefs[EDITS]) }

    override suspend fun editsFor(selection: PresetSelection?): Map<String, String> =
        edits.first().takeIf { it.appliesTo(selection) }?.values.orEmpty()

    override suspend fun set(selection: PresetSelection?, key: String, value: String) = update(selection) { it + (key to value) }

    override suspend fun reset(selection: PresetSelection?, key: String) = update(selection) { it - key }

    override suspend fun resetAll() {
        store.edit { it.remove(EDITS) }
    }

    /** Changes [selection]'s edits; edits made for another selection are dropped first. */
    private suspend fun update(selection: PresetSelection?, change: (Map<String, String>) -> Map<String, String>) {
        store.edit { prefs ->
            val current = decode(prefs[EDITS]).takeIf { it.appliesTo(selection) }?.values.orEmpty()
            val next = change(current)
            if (next.isEmpty()) prefs.remove(EDITS) else prefs[EDITS] = encode(SettingsEdits(selection, next))
        }
    }

    private fun decode(raw: String?): SettingsEdits {
        if (raw == null) return SettingsEdits.NONE
        val dto = runCatching { json.decodeFromString(EditsDto.serializer(), raw) }.getOrNull() ?: return SettingsEdits.NONE
        val selection = dto.printer?.let { PresetSelection(dto.vendor.orEmpty(), it, dto.process.orEmpty(), dto.filaments) }
        return SettingsEdits(selection, dto.values)
    }

    private fun encode(edits: SettingsEdits): String {
        val s = edits.selection
        return json.encodeToString(EditsDto.serializer(), EditsDto(s?.vendor, s?.printer, s?.process, s?.filaments.orEmpty(), edits.values))
    }

    @Serializable
    private data class EditsDto(
        val vendor: String? = null,
        val printer: String? = null,
        val process: String? = null,
        val filaments: List<String> = emptyList(),
        val values: Map<String, String> = emptyMap(),
    )

    private companion object {
        val EDITS = stringPreferencesKey("settings_edits")
    }
}
