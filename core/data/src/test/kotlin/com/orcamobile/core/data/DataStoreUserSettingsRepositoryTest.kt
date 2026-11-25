package com.orcamobile.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.orcamobile.core.model.BuildPlateSide
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintStartOptions
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
    fun `remembers the start-print options`() = runTest {
        val repository = repository()
        assertEquals(PrintStartOptions(), repository.printStartOptions())
        val options = PrintStartOptions(timeLapse = true, heatedBedLeveling = true, plate = BuildPlateSide.SMOOTH)
        repository.savePrintStartOptions(options)
        assertEquals(options, repository.printStartOptions())
    }
}
