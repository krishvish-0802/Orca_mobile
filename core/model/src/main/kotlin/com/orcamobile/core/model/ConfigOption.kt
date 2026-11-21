package com.orcamobile.core.model

/** How an option's value is written, as in OrcaSlicer's PrintConfigDef. */
enum class OptionType(val wire: String) {
    FLOAT("float"), FLOATS("floats"),
    INT("int"), INTS("ints"),
    STRING("string"), STRINGS("strings"),
    PERCENT("percent"), PERCENTS("percents"),
    FLOAT_OR_PERCENT("floatOrPercent"), FLOATS_OR_PERCENTS("floatsOrPercents"),
    BOOL("bool"), BOOLS("bools"),
    ENUM("enum"), ENUMS("enums"),
    POINT("point"), POINTS("points"),
    OTHER("other"),
    ;

    /** One value per extruder or filament, written comma-separated. */
    val isVector: Boolean get() = wire.endsWith("s") && this != OTHER

    companion object {
        fun fromWire(wire: String): OptionType = entries.firstOrNull { it.wire == wire } ?: OTHER
    }
}

/** One choice of an enumerated option: the stored [value] and its display [label]. */
data class OptionChoice(val value: String, val label: String)

/** One configuration option as OrcaSlicer defines it, for the settings screen. */
data class ConfigOption(
    val key: String,
    val type: OptionType,
    val label: String,
    val fullLabel: String,
    val category: String,
    val tooltip: String,
    /** e.g. "mm", "°C", "%"; empty when none. */
    val unit: String,
    val defaultValue: String,
    val min: Double? = null,
    val max: Double? = null,
    val choices: List<OptionChoice> = emptyList(),
)
