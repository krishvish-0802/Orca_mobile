package com.orcamobile.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.orcamobile.core.model.PresetSelection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

class DataStorePrintSettingsRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val centauri = PresetSelection("Elegoo", "Elegoo Centauri Carbon 0.4 nozzle", "0.20mm Standard", listOf("Generic PLA"))

    private fun TestScope.repository() = DataStorePrintSettingsRepository(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.newFile("settings.preferences_pb") },
    )

    @Test
    fun `keeps changes for the selection they were made for`() = runTest {
        val repository = repository()
        repository.set(centauri, "layer_height", "0.12")
        repository.set(centauri, "sparse_infill_density", "25%")
        assertEquals(mapOf("layer_height" to "0.12", "sparse_infill_density" to "25%"), repository.editsFor(centauri))
        assertEquals(emptyMap(), repository.editsFor(null))

        repository.reset(centauri, "layer_height")
        assertEquals(mapOf("sparse_infill_density" to "25%"), repository.editsFor(centauri))
        assertEquals(centauri, repository.edits.first().selection)
    }

    @Test
    fun `changes stay when filaments are added or recoloured`() = runTest {
        val repository = repository()
        repository.set(centauri, "enable_support", "1")
        val recoloured = centauri.copy(filamentColours = listOf("#A01828"))
        assertEquals(mapOf("enable_support" to "1"), repository.editsFor(recoloured))
        val two = recoloured.copy(filaments = centauri.filaments + centauri.filaments.first(), filamentColours = listOf("#A01828", "#FFFFFF"))
        assertEquals(mapOf("enable_support" to "1"), repository.editsFor(two))
        // A change made with two filaments keeps the earlier one.
        repository.set(two, "support_type", "tree(auto)")
        assertEquals(mapOf("enable_support" to "1", "support_type" to "tree(auto)"), repository.editsFor(centauri))
    }

    @Test
    fun `a change for other presets starts over`() = runTest {
        val repository = repository()
        repository.set(centauri, "layer_height", "0.12")
        val other = centauri.copy(process = "0.12mm Fine")
        repository.set(other, "wall_loops", "3")
        assertEquals(mapOf("wall_loops" to "3"), repository.editsFor(other))
        assertEquals(emptyMap(), repository.editsFor(centauri))

        repository.resetAll()
        assertEquals(emptyMap(), repository.editsFor(other))
    }
}
