package com.orcamobile.feature.settings

import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.OptionType

/**
 * Reading and writing option values as profiles serialize them. Per-extruder
 * options (one value per extruder or filament) are edited one value at a time:
 * the first (a single-extruder print's) unless an index is given, the others
 * kept.
 */
object SettingValues {

    /** The value of an unset override ([ConfigOption.nullable] options), which defers to the printer. */
    const val UNSET = "nil"

    /** The value the screen shows and edits: one element, unescaped, a percentage without its "%". */
    fun editable(option: ConfigOption, serialized: String, index: Int = 0): String {
        val first = at(elements(option, serialized), index)
        return when {
            option.type == OptionType.STRING -> ConfigText.unescape(serialized)
            option.type.isPercentOnly -> first.removeSuffix("%")
            else -> first
        }
    }

    /** Whether the edited element is an unset override. */
    fun isUnset(option: ConfigOption, serialized: String, index: Int = 0): Boolean =
        option.nullable && at(elements(option, serialized), index) == UNSET

    /** [serialized] with its edited element replaced by [edited] (or by [UNSET]). */
    fun write(option: ConfigOption, serialized: String, edited: String, index: Int = 0): String {
        val value = when {
            edited == UNSET && option.nullable -> UNSET
            option.type == OptionType.STRING -> return ConfigText.escape(edited)
            option.type == OptionType.STRINGS -> edited
            option.type.isPercentOnly -> "${edited.trim().removeSuffix("%")}%"
            else -> edited.trim()
        }
        if (!option.type.isVector) return value
        val all = elements(option, serialized).toMutableList()
        // A shorter list (one value for every filament) takes its last value up to the edited one.
        while (all.size <= index) all += all.lastOrNull().orEmpty()
        all[index] = value
        return join(option, all)
    }

    /** Element [index] of a per-extruder value alone, serialized as a one-value list; a single value as it is. */
    fun element(option: ConfigOption, serialized: String, index: Int): String =
        if (!option.type.isVector || option.type == OptionType.POINTS) serialized else join(option, listOf(at(elements(option, serialized), index)))

    private fun join(option: ConfigOption, all: List<String>) =
        if (option.type == OptionType.STRINGS) ConfigText.joinStrings(all) else all.joinToString(",")

    /** Element [index], or the last for a shorter list. */
    private fun at(elements: List<String>, index: Int): String = elements.getOrNull(index) ?: elements.lastOrNull().orEmpty()

    /** Why [edited] is not a valid value of [option], or null when it is. */
    fun problem(option: ConfigOption, edited: String): Problem? {
        if (edited == UNSET && option.nullable) return null
        val text = edited.trim()
        val numeric = option.type in NUMERIC
        if (!numeric) return null
        if (text.isEmpty()) return Problem.Empty
        val number = when (option.type) {
            OptionType.INT, OptionType.INTS -> text.toIntOrNull()?.toDouble() ?: return Problem.NotAWholeNumber
            else -> text.removeSuffix("%").toDoubleOrNull() ?: return Problem.NotANumber
        }
        // A percentage of a float-or-percent option is not on the option's own scale.
        if (option.type.isFloatOrPercent && text.endsWith("%")) return null
        option.min?.let { if (number < it) return Problem.BelowMin(it) }
        option.max?.let { if (number > it) return Problem.AboveMax(it) }
        return null
    }

    /**
     * How a value reads in a row: the choice's label, a number with its unit, or
     * the first line of a text. Switches and unset overrides are worded by the UI.
     */
    fun display(option: ConfigOption, serialized: String, index: Int = 0): String {
        val value = editable(option, serialized, index)
        option.choices.firstOrNull { it.value == value }?.let { return it.label }
        if (option.type == OptionType.STRING || option.type == OptionType.STRINGS) {
            val lines = value.lines()
            return if (lines.size > 1) "${lines.first().take(MAX_PREVIEW)} …" else value.take(MAX_PREVIEW)
        }
        if (option.unit.isBlank() || value.isEmpty()) return value
        return if (option.type.isPercentOnly) "$value%" else "$value ${option.unit}"
    }

    /** The elements of a per-extruder value; the whole value for a single one. */
    private fun elements(option: ConfigOption, serialized: String): List<String> = when {
        option.type == OptionType.STRINGS -> ConfigText.splitStrings(serialized).ifEmpty { listOf("") }
        option.type.isVector && option.type != OptionType.POINTS -> serialized.split(',')
        else -> listOf(serialized)
    }

    sealed interface Problem {
        data object Empty : Problem
        data object NotANumber : Problem
        data object NotAWholeNumber : Problem
        data class BelowMin(val min: Double) : Problem
        data class AboveMax(val max: Double) : Problem
    }

    private const val MAX_PREVIEW = 40
    private val NUMERIC = setOf(
        OptionType.FLOAT, OptionType.FLOATS, OptionType.INT, OptionType.INTS, OptionType.PERCENT, OptionType.PERCENTS,
        OptionType.FLOAT_OR_PERCENT, OptionType.FLOATS_OR_PERCENTS,
    )
    private val OptionType.isPercentOnly get() = this == OptionType.PERCENT || this == OptionType.PERCENTS
    private val OptionType.isFloatOrPercent get() = this == OptionType.FLOAT_OR_PERCENT || this == OptionType.FLOATS_OR_PERCENTS
}
