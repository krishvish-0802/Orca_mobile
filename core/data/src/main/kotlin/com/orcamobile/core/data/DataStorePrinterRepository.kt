package com.orcamobile.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.orcamobile.core.domain.printer.PrinterRepository
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.model.SavedPrinter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's printers in the app's Preferences DataStore, as one JSON list.
 * The single print host the app kept before is the first printer until the
 * list is first saved.
 */
@Singleton
class DataStorePrinterRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) : PrinterRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override val printers: Flow<List<SavedPrinter>> = store.data.map(::printersOf)

    override val selected: Flow<String?> = store.data.map { prefs ->
        val list = printersOf(prefs)
        prefs[SELECTED]?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
    }

    override suspend fun save(printer: SavedPrinter) {
        store.edit { prefs ->
            val list = printersOf(prefs)
            val next = if (list.any { it.id == printer.id }) list.map { if (it.id == printer.id) printer else it } else list + printer
            write(prefs, next)
            if (prefs[SELECTED] == null) prefs[SELECTED] = printer.id
        }
    }

    override suspend fun remove(id: String) {
        store.edit { prefs ->
            write(prefs, printersOf(prefs).filterNot { it.id == id })
            if (prefs[SELECTED] == id) prefs.remove(SELECTED)
        }
    }

    override suspend fun select(id: String) {
        store.edit { prefs ->
            prefs[SELECTED] = id
            write(prefs, printersOf(prefs))
        }
    }

    private fun write(prefs: MutablePreferences, list: List<SavedPrinter>) {
        prefs[PRINTERS] = json.encodeToString(ListSerializer(PrinterDto.serializer()), list.map(PrinterDto::of))
        // The slice screen still sends to "the" print host: the selected printer's.
        (list.firstOrNull { it.id == prefs[SELECTED] } ?: list.firstOrNull())?.host?.let { host ->
            prefs[HOST_TYPE] = host.type.name
            prefs[HOST_ADDRESS] = host.baseUrl
            val key = host.apiKey
            if (key.isNullOrBlank()) prefs.remove(HOST_API_KEY) else prefs[HOST_API_KEY] = key
        }
    }

    private fun printersOf(prefs: Preferences): List<SavedPrinter> {
        prefs[PRINTERS]?.let { raw ->
            return runCatching { json.decodeFromString(ListSerializer(PrinterDto.serializer()), raw) }.getOrDefault(emptyList()).mapNotNull { it.toModel() }
        }
        // Before the list: the one print host set on the slice screen.
        val type = prefs[HOST_TYPE]?.let { name -> PrintHostType.entries.firstOrNull { it.name == name } } ?: return emptyList()
        val address = prefs[HOST_ADDRESS]?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(SavedPrinter(LEGACY_ID, type.displayName, PrintHostConfig(type, address, prefs[HOST_API_KEY])))
    }

    @Serializable
    private data class PrinterDto(
        val id: String,
        val name: String,
        val type: String,
        val address: String,
        val apiKey: String? = null,
        val preset: String? = null,
    ) {
        fun toModel(): SavedPrinter? {
            val hostType = PrintHostType.entries.firstOrNull { it.name == type } ?: return null
            return SavedPrinter(id, name, PrintHostConfig(hostType, address, apiKey), preset)
        }

        companion object {
            fun of(printer: SavedPrinter) =
                PrinterDto(printer.id, printer.name, printer.host.type.name, printer.host.baseUrl, printer.host.apiKey, printer.preset)
        }
    }

    private companion object {
        const val LEGACY_ID = "printer-1"
        val PRINTERS = stringPreferencesKey("printers")
        val SELECTED = stringPreferencesKey("selected_printer")
        // Shared with DataStoreUserSettingsRepository's print host.
        val HOST_TYPE = stringPreferencesKey("host_type")
        val HOST_ADDRESS = stringPreferencesKey("host_address")
        val HOST_API_KEY = stringPreferencesKey("host_api_key")
    }
}
