package com.orcamobile.core.domain.model

import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintStartOptions

/** Choices the app remembers between launches. */
interface UserSettingsRepository {
    suspend fun presetSelection(): PresetSelection?

    suspend fun savePresetSelection(selection: PresetSelection)

    suspend fun printHost(): PrintHostConfig?

    suspend fun savePrintHost(host: PrintHostConfig)

    /** The start-print options last used; defaults when never set. */
    suspend fun printStartOptions(): PrintStartOptions

    suspend fun savePrintStartOptions(options: PrintStartOptions)
}
