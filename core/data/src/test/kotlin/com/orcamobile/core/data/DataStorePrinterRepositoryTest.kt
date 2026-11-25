package com.orcamobile.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.model.SavedPrinter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStorePrinterRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val centauri = SavedPrinter("a", "Centauri", PrintHostConfig(PrintHostType.ELEGOO_LINK, "192.168.1.40"), "Elegoo Centauri Carbon 0.4 nozzle")
    private val voron = SavedPrinter("b", "Voron", PrintHostConfig(PrintHostType.MOONRAKER, "voron.local:7125", "key"))

    @Test
    fun `printers are kept with the first one selected, and another can be chosen`() = runTest {
        val repository = DataStorePrinterRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.newFile("p.preferences_pb") })
        assertEquals(emptyList(), repository.printers.first())
        assertNull(repository.selected.first())

        repository.save(centauri)
        repository.save(voron)
        assertEquals(listOf(centauri, voron), repository.printers.first())
        assertEquals("a", repository.selected.first())

        repository.select("b")
        repository.save(voron.copy(name = "Voron 2.4"))
        assertEquals(listOf("Centauri", "Voron 2.4"), repository.printers.first().map { it.name })
        assertEquals("b", repository.selected.first())

        // Removing the chosen printer falls back to the first left.
        repository.remove("b")
        assertEquals("a", repository.selected.first())
    }

    @Test
    fun `the print host set in 0_3 becomes the first printer`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.newFile("p.preferences_pb") }
        store.edit {
            it[stringPreferencesKey("host_type")] = "OCTOPRINT"
            it[stringPreferencesKey("host_address")] = "octopi.local"
            it[stringPreferencesKey("host_api_key")] = "key"
        }
        val repository = DataStorePrinterRepository(store)
        val legacy = repository.printers.first().single()
        assertEquals(PrintHostConfig(PrintHostType.OCTOPRINT, "octopi.local", "key"), legacy.host)
        assertEquals(legacy.id, repository.selected.first())

        // Adding a printer keeps it, and the old keys are no longer read.
        repository.save(centauri)
        assertEquals(listOf(legacy.id, "a"), repository.printers.first().map { it.id })
        assertNull(store.data.first()[stringPreferencesKey("host_address")])
    }
}
