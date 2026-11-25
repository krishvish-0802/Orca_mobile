package com.orcamobile.core.model

import org.junit.Test
import kotlin.test.assertEquals

class ConfigTextTest {

    @Test
    fun `strings round-trip through OrcaSlicer's escaping`() {
        val gcode = "G28 ; home\nM104 S[nozzle_temperature]\nM117 \"Printing\" \\ done"
        val escaped = ConfigText.escape(gcode)
        assertEquals("G28 ; home\\nM104 S[nozzle_temperature]\\nM117 \\\"Printing\\\" \\\\ done", escaped)
        assertEquals(gcode, ConfigText.unescape(escaped))
    }

    @Test
    fun `string lists are split and joined like escape_strings_cstyle`() {
        assertEquals(listOf("PLA", "PETG"), ConfigText.splitStrings("PLA;PETG"))
        assertEquals(listOf("a b", "c;d", ""), ConfigText.splitStrings("\"a b\";\"c;d\";"))
        assertEquals("PLA;\"Silk PLA\"", ConfigText.joinStrings(listOf("PLA", "Silk PLA")))
        assertEquals("\"\"", ConfigText.joinStrings(listOf("")))
        val notes = listOf("line one\nline \"two\"")
        assertEquals(notes, ConfigText.splitStrings(ConfigText.joinStrings(notes)))
    }
}
