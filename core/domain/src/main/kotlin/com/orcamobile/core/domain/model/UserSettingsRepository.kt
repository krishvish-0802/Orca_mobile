package com.orcamobile.core.domain.model

import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintStartOptions

/** Choices the app remembers between launches. */
interface UserSettingsRepository {
    suspend fun presetSelection(): PresetSelection?

    suspend fun savePresetSelection(selection: PresetSelection)

    /** The start-print options last used; defaults when never set. */
    suspend fun printStartOptions(): PrintStartOptions

    suspend fun savePrintStartOptions(options: PrintStartOptions)

    /** Whether the first-run setup was finished. */
    suspend fun setupDone(): Boolean = true

    suspend fun saveSetupDone() = Unit

    /** The filament presets [printer] lists (the desktop's filament selection); empty for all it suits. */
    suspend fun shownFilaments(printer: String): Set<String> = emptySet()

    suspend fun saveShownFilaments(printer: String, names: Set<String>) = Unit
}
