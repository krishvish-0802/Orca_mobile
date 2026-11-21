package com.orcamobile.feature.settings

import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.OptionChoice
import com.orcamobile.core.model.OptionType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SettingValuesTest {

    private fun option(
        type: OptionType,
        unit: String = "",
        min: Double? = null,
        max: Double? = null,
        choices: List<OptionChoice> = emptyList(),
        nullable: Boolean = false,
    ) = ConfigOption("k", type, "Label", "Full label", "", "", unit, "", min, max, choices, nullable = nullable)

    @Test
    fun `per-filament values are edited through the first one`() {
        val temperature = option(OptionType.INTS, "°C", min = 0.0, max = 500.0)
        assertEquals("220", SettingValues.editable(temperature, "220,230"))
        assertEquals("215,230", SettingValues.write(temperature, "220,230", "215"))
        assertEquals("220 °C", SettingValues.display(temperature, "220,230"))
    }

    @Test
    fun `percentages are edited without the percent sign`() {
        val density = option(OptionType.PERCENT, "%", min = 0.0, max = 100.0)
        assertEquals("15", SettingValues.editable(density, "15%"))
        assertEquals("25%", SettingValues.write(density, "15%", "25"))
        assertEquals("15%", SettingValues.display(density, "15%"))
        assertIs<SettingValues.Problem.AboveMax>(SettingValues.problem(density, "120"))
    }

    @Test
    fun `numbers are checked against the option's limits`() {
        val walls = option(OptionType.INT, min = 0.0, max = 1000.0)
        assertNull(SettingValues.problem(walls, "3"))
        assertIs<SettingValues.Problem.NotAWholeNumber>(SettingValues.problem(walls, "2.5"))
        assertIs<SettingValues.Problem.BelowMin>(SettingValues.problem(walls, "-1"))
        assertIs<SettingValues.Problem.Empty>(SettingValues.problem(walls, " "))
        assertIs<SettingValues.Problem.NotANumber>(SettingValues.problem(option(OptionType.FLOAT), "abc"))
    }

    @Test
    fun `float-or-percent takes either`() {
        val width = option(OptionType.FLOAT_OR_PERCENT, "mm or %", min = 0.0, max = 10.0)
        assertNull(SettingValues.problem(width, "0.42"))
        assertNull(SettingValues.problem(width, "105%"))
        assertEquals("105%", SettingValues.write(width, "0.42", "105%"))
    }

    @Test
    fun `choices show their labels`() {
        val pattern = option(OptionType.ENUM, choices = listOf(OptionChoice("gyroid", "Gyroid"), OptionChoice("grid", "Grid")))
        assertEquals("Gyroid", SettingValues.display(pattern, "gyroid"))
    }

    @Test
    fun `multi-line text is edited unescaped and stored escaped`() {
        val gcode = option(OptionType.STRING)
        assertEquals("G28\nG1 Z5", SettingValues.editable(gcode, "G28\\nG1 Z5"))
        assertEquals("G28\\nM84", SettingValues.write(gcode, "G28", "G28\nM84"))
        assertEquals("G28 …", SettingValues.display(gcode, "G28\\nM84"))
    }

    @Test
    fun `string lists keep their other elements`() {
        val type = option(OptionType.STRINGS)
        assertEquals("PLA", SettingValues.editable(type, "PLA;PETG"))
        assertEquals("\"Silk PLA\";PETG", SettingValues.write(type, "PLA;PETG", "Silk PLA"))
    }

    @Test
    fun `an override can be left unset`() {
        val retraction = option(OptionType.FLOATS, "mm", min = 0.0, nullable = true)
        assertEquals(true, SettingValues.isUnset(retraction, "nil"))
        assertEquals("0.8", SettingValues.write(retraction, "nil", "0.8"))
        assertEquals("nil", SettingValues.write(retraction, "0.8", SettingValues.UNSET))
        assertNull(SettingValues.problem(retraction, SettingValues.UNSET))
    }
}
