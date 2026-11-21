package com.orcamobile.feature.settings

import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.OptionType

/**
 * Reading and writing option values as profiles serialize them. Per-extruder
 * options (one value per filament, comma separated) are edited through their
 * first value, which is the one a single-filament print uses.
 */
object SettingValues {

    /** The value the screen shows and edits: the first one of a per-extruder option, without a "%". */
    fun editable(option: ConfigOption, serialized: String): String {
        val first = if (option.type.isVector) serialized.split(',').firstOrNull().orEmpty() else serialized
        return if (option.type.isPercentOnly) first.removeSuffix("%") else first
    }

    /** [serialized] with its editable value replaced by [edited]. */
    fun write(option: ConfigOption, serialized: String, edited: String): String {
        val value = if (option.type.isPercentOnly) "${edited.trim().removeSuffix("%")}%" else edited.trim()
        if (!option.type.isVector) return value
        val rest = serialized.split(',').drop(1)
        return (listOf(value) + rest).joinToString(",")
    }

    /** Why [edited] is not a valid value of [option], or null when it is. */
    fun problem(option: ConfigOption, edited: String): Problem? {
        val text = edited.trim()
        if (text.isEmpty() && option.type != OptionType.STRING && option.type != OptionType.STRINGS) return Problem.Empty
        val number = when (option.type) {
            OptionType.INT, OptionType.INTS -> text.toIntOrNull()?.toDouble() ?: return Problem.NotAWholeNumber
            OptionType.FLOAT, OptionType.FLOATS, OptionType.PERCENT, OptionType.PERCENTS ->
                text.removeSuffix("%").toDoubleOrNull() ?: return Problem.NotANumber
            OptionType.FLOAT_OR_PERCENT, OptionType.FLOATS_OR_PERCENTS ->
                text.removeSuffix("%").toDoubleOrNull() ?: return Problem.NotANumber
            else -> return null
        }
        // A percentage of a float-or-percent option is not on the option's own scale.
        if (option.type.isFloatOrPercent && text.endsWith("%")) return null
        option.min?.let { if (number < it) return Problem.BelowMin(it) }
        option.max?.let { if (number > it) return Problem.AboveMax(it) }
        return null
    }

    /** How a value reads in a row: the choice's label, "On"/"Off" left to the UI, numbers with their unit. */
    fun display(option: ConfigOption, serialized: String): String {
        val value = editable(option, serialized)
        option.choices.firstOrNull { it.value == value }?.let { return it.label }
        if (option.unit.isBlank() || value.isEmpty()) return value
        return if (option.type.isPercentOnly) "$value%" else "$value ${option.unit}"
    }

    sealed interface Problem {
        data object Empty : Problem
        data object NotANumber : Problem
        data object NotAWholeNumber : Problem
        data class BelowMin(val min: Double) : Problem
        data class AboveMax(val max: Double) : Problem
    }

    private val OptionType.isPercentOnly get() = this == OptionType.PERCENT || this == OptionType.PERCENTS
    private val OptionType.isFloatOrPercent get() = this == OptionType.FLOAT_OR_PERCENT || this == OptionType.FLOATS_OR_PERCENTS
}
