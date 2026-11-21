package com.orcamobile.feature.settings

import android.content.Context
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.PresetOptionKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** A page of a settings tab, as the desktop app's Process, Filament and Printer tabs have them. */
data class SettingsPage(val title: String, val groups: List<SettingsGroup>)

/** A titled group of options on a page. */
data class SettingsGroup(val title: String, val keys: List<String>)

/** The pages of each tab. */
data class SettingsTabs(val pages: Map<PresetKind, List<SettingsPage>>) {
    fun of(kind: PresetKind): List<SettingsPage> = pages[kind].orEmpty()

    val keys: List<String> get() = pages.values.flatten().flatMap { page -> page.groups.flatMap { it.keys } }.distinct()

    /**
     * Adds, on a last "Other" page of each tab, the options the preset kind holds
     * that the desktop layout does not place, so every option the engine
     * accepts can be changed. Internal bookkeeping fields and options the engine
     * does not describe are left out.
     */
    fun completedWith(presetKeys: PresetOptionKeys, described: Map<String, ConfigOption>, otherTitle: String): SettingsTabs {
        val placed = keys.toSet()
        return SettingsTabs(
            PresetKind.entries.associateWith { kind ->
                val pages = of(kind)
                val rest = presetKeys.of(kind).filter { key ->
                    key !in placed && key !in INTERNAL_KEYS && described[key]?.let { it.label != it.key } == true
                }
                if (rest.isEmpty()) pages else pages + SettingsPage(otherTitle, listOf(SettingsGroup(otherTitle, rest)))
            },
        )
    }

    companion object {
        /** Preset bookkeeping the desktop app shows nowhere either. */
        val INTERNAL_KEYS = setOf(
            "inherits", "compatible_printers", "compatible_printers_condition", "compatible_prints",
            "compatible_prints_condition", "print_settings_id", "filament_settings_id", "printer_settings_id",
            "printer_model", "printer_variant", "printer_technology", "default_print_profile", "default_filament_profile",
            "upward_compatible_machine", "print_extruder_id", "print_extruder_variant", "filament_extruder_variant",
            "printer_extruder_id", "printer_extruder_variant", "extruder_variant_list", "plugins", "version", "from",
            "filament_vendor", "printer_notes", "printhost_apikey", "printhost_password", "printhost_user", "print_host",
            "print_host_webui", "printhost_cafile", "printhost_port", "printhost_authorization_type",
            "printhost_ssl_ignore_revoke", "host_type", "flashforge_serial_number",
        )

        private val json = Json { ignoreUnknownKeys = true }

        /** Parses the layout tools/extract_tab_layout.py writes from upstream's Tab.cpp. */
        fun parse(text: String): SettingsTabs {
            val dto = json.decodeFromString(LayoutDto.serializer(), text)
            fun pages(list: List<PageDto>) = list.map { page -> SettingsPage(page.page, page.groups.map { SettingsGroup(it.group, it.options) }) }
            return SettingsTabs(
                mapOf(
                    PresetKind.PROCESS to pages(dto.tabs.process),
                    PresetKind.FILAMENT to pages(dto.tabs.filament),
                    PresetKind.PRINTER to pages(dto.tabs.printer),
                ),
            )
        }
    }
}

/** Where the settings layout comes from. */
fun interface SettingsLayoutSource {
    fun load(): SettingsTabs
}

/** The bundled layout (assets/settings_layout.json, generated from the pinned upstream's Tab.cpp). */
class AssetSettingsLayoutSource @Inject constructor(@ApplicationContext private val context: Context) : SettingsLayoutSource {
    override fun load(): SettingsTabs = context.assets.open(ASSET).bufferedReader().use { SettingsTabs.parse(it.readText()) }

    private companion object {
        const val ASSET = "settings_layout.json"
    }
}

@Serializable
private data class LayoutDto(val source: String = "", val tabs: TabsDto)

@Serializable
private data class TabsDto(
    val process: List<PageDto> = emptyList(),
    val filament: List<PageDto> = emptyList(),
    val printer: List<PageDto> = emptyList(),
)

@Serializable
private data class PageDto(val page: String, val groups: List<GroupDto>)

@Serializable
private data class GroupDto(val group: String, val options: List<String>)
