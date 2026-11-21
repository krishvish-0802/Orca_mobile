package com.orcamobile.feature.settings

import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.OptionType
import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.PresetOptionKeys
import org.junit.Test
import kotlin.test.assertEquals

class SettingsTabsTest {

    private val layout = SettingsTabs.parse(
        """{"source":"x","tabs":{"process":[{"page":"Quality","groups":[{"group":"Layer height","options":["layer_height"]}]}],
            "filament":[],"printer":[{"page":"Basic information","groups":[{"group":"Printable space","options":["printable_height"]}]}]}}""",
    )

    private fun option(key: String, label: String = key.replace('_', ' ')) =
        ConfigOption(key, OptionType.FLOAT, label, label, "", "", "", "")

    @Test
    fun `parses the generated layout`() {
        assertEquals(listOf("Quality"), layout.of(PresetKind.PROCESS).map { it.title })
        assertEquals(listOf("layer_height", "printable_height"), layout.keys)
    }

    @Test
    fun `every option of a preset kind ends up on a page`() {
        val presetKeys = PresetOptionKeys(
            process = listOf("layer_height", "ironing_expansion", "inherits", "unlabelled"),
            filament = listOf("filament_flush_temp"),
            printer = listOf("printable_height"),
        )
        val described = listOf(
            option("layer_height"), option("ironing_expansion"), option("inherits"), option("unlabelled", label = "unlabelled"),
            option("filament_flush_temp"), option("printable_height"),
        ).associateBy { it.key }

        val complete = layout.completedWith(presetKeys, described, "Other")

        val process = complete.of(PresetKind.PROCESS)
        assertEquals(listOf("Quality", "Other"), process.map { it.title })
        // Internal fields and options without a label stay out.
        assertEquals(listOf("ironing_expansion"), process.last().groups.single().keys)
        assertEquals(listOf("filament_flush_temp"), complete.of(PresetKind.FILAMENT).single().groups.single().keys)
        assertEquals(listOf("Basic information"), complete.of(PresetKind.PRINTER).map { it.title })
    }
}
