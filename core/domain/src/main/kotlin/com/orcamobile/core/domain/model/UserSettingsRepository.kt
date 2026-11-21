package com.orcamobile.core.domain.model

import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintHostConfig

/** Choices the app remembers between launches. */
interface UserSettingsRepository {
    suspend fun presetSelection(): PresetSelection?

    suspend fun savePresetSelection(selection: PresetSelection)

    suspend fun printHost(): PrintHostConfig?

    suspend fun savePrintHost(host: PrintHostConfig)
}
