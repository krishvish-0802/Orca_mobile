package com.orcamobile.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.model.BuildPlateSide
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.model.PrintStartOptions
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores user choices in a Preferences DataStore. The API key is kept with the
 * other settings in app-private storage; moving it to an encrypted store is
 * tracked for the device milestone (M4).
 */
@Singleton
class DataStoreUserSettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) : UserSettingsRepository {

    override suspend fun presetSelection(): PresetSelection? {
        val prefs = store.data.first()
        val vendor = prefs[VENDOR] ?: return null
        val printer = prefs[PRINTER] ?: return null
        val process = prefs[PROCESS] ?: return null
        val filaments = prefs[FILAMENTS]?.split(LIST_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()
        if (filaments.isEmpty()) return null
        // Kept with the filaments, an empty entry where a slot follows its preset.
        val colours = prefs[FILAMENT_COLOURS]?.split(LIST_SEPARATOR).orEmpty().take(filaments.size)
        return PresetSelection(vendor, printer, process, filaments, colours.takeIf { c -> c.any { it.isNotEmpty() } }.orEmpty())
    }

    override suspend fun savePresetSelection(selection: PresetSelection) {
        store.edit {
            it[VENDOR] = selection.vendor
            it[PRINTER] = selection.printer
            it[PROCESS] = selection.process
            it[FILAMENTS] = selection.filaments.joinToString(LIST_SEPARATOR)
            it[FILAMENT_COLOURS] = selection.filamentColours.joinToString(LIST_SEPARATOR)
        }
    }

    override suspend fun printHost(): PrintHostConfig? {
        val prefs = store.data.first()
        val type = prefs[HOST_TYPE]?.let { name -> PrintHostType.entries.firstOrNull { it.name == name } } ?: return null
        val address = prefs[HOST_ADDRESS] ?: return null
        return PrintHostConfig(type, address, prefs[HOST_API_KEY])
    }

    override suspend fun savePrintHost(host: PrintHostConfig) {
        store.edit {
            it[HOST_TYPE] = host.type.name
            it[HOST_ADDRESS] = host.baseUrl
            val key = host.apiKey
            if (key.isNullOrBlank()) it.remove(HOST_API_KEY) else it[HOST_API_KEY] = key
        }
    }

    override suspend fun printStartOptions(): PrintStartOptions {
        val prefs = store.data.first()
        val defaults = PrintStartOptions()
        return PrintStartOptions(
            timeLapse = prefs[START_TIME_LAPSE] ?: defaults.timeLapse,
            heatedBedLeveling = prefs[START_BED_LEVELING] ?: defaults.heatedBedLeveling,
            plate = prefs[START_PLATE]?.let { name -> BuildPlateSide.entries.firstOrNull { it.name == name } } ?: defaults.plate,
        )
    }

    override suspend fun savePrintStartOptions(options: PrintStartOptions) {
        store.edit {
            it[START_TIME_LAPSE] = options.timeLapse
            it[START_BED_LEVELING] = options.heatedBedLeveling
            it[START_PLATE] = options.plate.name
        }
    }

    /** Finished, or never needed: a selection saved before the setup existed. */
    override suspend fun setupDone(): Boolean {
        val prefs = store.data.first()
        return prefs[SETUP_DONE] ?: (prefs[VENDOR] != null)
    }

    override suspend fun saveSetupDone() {
        store.edit { it[SETUP_DONE] = true }
    }

    override suspend fun shownFilaments(printer: String): Set<String> =
        store.data.first()[shownFilamentsKey(printer)]?.split(LIST_SEPARATOR)?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    override suspend fun saveShownFilaments(printer: String, names: Set<String>) {
        store.edit {
            if (names.isEmpty()) it.remove(shownFilamentsKey(printer)) else it[shownFilamentsKey(printer)] = names.joinToString(LIST_SEPARATOR)
        }
    }

    private companion object {
        val SETUP_DONE = booleanPreferencesKey("setup_done")
        fun shownFilamentsKey(printer: String) = stringPreferencesKey("shown_filaments/$printer")

        // Preset names may contain commas and spaces, never this control character.
        const val LIST_SEPARATOR = "\u001F"
        val VENDOR = stringPreferencesKey("preset_vendor")
        val PRINTER = stringPreferencesKey("preset_printer")
        val PROCESS = stringPreferencesKey("preset_process")
        val FILAMENTS = stringPreferencesKey("preset_filaments")
        val FILAMENT_COLOURS = stringPreferencesKey("preset_filament_colours")
        val HOST_TYPE = stringPreferencesKey("host_type")
        val HOST_ADDRESS = stringPreferencesKey("host_address")
        val HOST_API_KEY = stringPreferencesKey("host_api_key")
        val START_TIME_LAPSE = booleanPreferencesKey("start_time_lapse")
        val START_BED_LEVELING = booleanPreferencesKey("start_bed_leveling")
        val START_PLATE = stringPreferencesKey("start_plate")
    }
}
