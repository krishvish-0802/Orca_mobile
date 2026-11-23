package com.orcamobile.core.domain

import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.presets.PresetPicker
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.CompatiblePresets
import com.orcamobile.core.model.FilamentSlot
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintStartOptions
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PresetPickerTest {

    private class Catalog : PresetCatalog {
        override suspend fun vendors() = Result.success(listOf("Elegoo"))
        override suspend fun printers(vendor: String) = Result.success(listOf("Centauri Carbon"))
        override suspend fun compatiblePresets(vendor: String, printer: String) =
            Result.success(CompatiblePresets(listOf("0.20mm Standard"), listOf("PLA", "PETG"), "0.20mm Standard", "PLA"))
        override suspend fun bedShape(vendor: String, printer: String) = Result.success(BedShape(emptyList(), 250.0))

        /** Presets print white unless a slot has its own colour. */
        override suspend fun filamentColours(selection: PresetSelection) = Result.success(
            selection.filaments.indices.map { selection.filamentColours.getOrNull(it)?.ifEmpty { null } ?: "#FFFFFF" },
        )
    }

    private class Settings(var saved: PresetSelection?) : UserSettingsRepository {
        override suspend fun presetSelection() = saved
        override suspend fun savePresetSelection(selection: PresetSelection) { saved = selection }
        override suspend fun printHost(): PrintHostConfig? = null
        override suspend fun savePrintHost(host: PrintHostConfig) = Unit
        override suspend fun printStartOptions() = PrintStartOptions()
        override suspend fun savePrintStartOptions(options: PrintStartOptions) = Unit
    }

    private val start = PresetSelection("Elegoo", "Centauri Carbon", "0.20mm Standard", listOf("PLA"))

    @Test
    fun `filaments are added, coloured, changed and taken away, and remembered`() = runTest {
        val settings = Settings(start)
        val picker = PresetPicker(Catalog(), settings)
        picker.load()
        assertEquals(listOf(FilamentSlot("PLA")), picker.choices.value.slots)
        assertEquals(listOf("#FFFFFF"), picker.choices.value.slotColours)

        picker.addFilament()
        picker.selectFilament(1, "PETG")
        picker.setFilamentColour(1, "#FF0000")
        assertEquals(listOf(FilamentSlot("PLA"), FilamentSlot("PETG", "#FF0000")), picker.choices.value.slots)
        assertEquals(listOf("#FFFFFF", "#FF0000"), picker.choices.value.slotColours)
        assertEquals(start.copy(filaments = listOf("PLA", "PETG"), filamentColours = listOf("", "#FF0000")), settings.saved)

        picker.removeFilament(0)
        assertEquals(listOf(FilamentSlot("PETG", "#FF0000")), picker.choices.value.slots)
        picker.removeFilament(0)   // the last one stays
        assertEquals(1, picker.choices.value.slots.size)
    }

    @Test
    fun `saved filaments come back with their colours, and unknown ones give way to the default`() = runTest {
        val saved = start.copy(filaments = listOf("PETG", "Gone"), filamentColours = listOf("#00FF00", ""))
        val picker = PresetPicker(Catalog(), Settings(saved))
        picker.load()
        assertEquals(listOf(FilamentSlot("PETG", "#00FF00"), FilamentSlot("PLA")), picker.choices.value.slots)
        assertEquals("PETG", picker.choices.value.filament)
    }

    @Test
    fun `another printer starts again from one filament`() = runTest {
        val picker = PresetPicker(Catalog(), Settings(start))
        picker.load()
        picker.addFilament()
        picker.selectPrinter("Centauri Carbon")
        assertEquals(listOf(FilamentSlot("PLA")), picker.choices.value.slots)
        assertNull(picker.choices.value.slots.single().colour)
    }
}
