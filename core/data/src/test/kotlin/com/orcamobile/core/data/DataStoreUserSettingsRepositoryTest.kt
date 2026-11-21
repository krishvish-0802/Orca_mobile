package com.orcamobile.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DataStoreUserSettingsRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun TestScope.repository() = DataStoreUserSettingsRepository(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.newFile("settings.preferences_pb") },
    )

    @Test
    fun `remembers a preset selection with names containing commas`() = runTest {
        val repository = repository()
        assertNull(repository.presetSelection())

        val selection = PresetSelection(
            vendor = "Creality",
            printer = "Creality Ender-3 Pro 0.4 nozzle",
            process = "0.20mm Standard @Creality Ender3Pro",
            filaments = listOf("Generic PLA, matte", "Generic PETG"),
        )
        repository.savePresetSelection(selection)

        assertEquals(selection, repository.presetSelection())
    }

    @Test
    fun `remembers a print host and drops a blank api key`() = runTest {
        val repository = repository()
        repository.savePrintHost(PrintHostConfig(PrintHostType.OCTOPRINT, "octopi.local", "key"))
        assertEquals(PrintHostConfig(PrintHostType.OCTOPRINT, "octopi.local", "key"), repository.printHost())

        repository.savePrintHost(PrintHostConfig(PrintHostType.MOONRAKER, "192.168.1.50:7125", ""))
        assertEquals(PrintHostConfig(PrintHostType.MOONRAKER, "192.168.1.50:7125", null), repository.printHost())
    }
}
