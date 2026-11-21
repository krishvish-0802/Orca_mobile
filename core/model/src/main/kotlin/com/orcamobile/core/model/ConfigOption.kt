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

/**
 * How much of the settings an option belongs to, as the desktop app's
 * Simple / Advanced / Expert switch shows them. Develop options are hidden there.
 */
enum class OptionMode(val wire: String) {
    SIMPLE("simple"), ADVANCED("advanced"), EXPERT("expert"), DEVELOP("develop");

    companion object {
        fun fromWire(wire: String): OptionMode = entries.firstOrNull { it.wire == wire } ?: EXPERT
    }
}

/** The three kinds of preset a slice combines. */
enum class PresetKind { PROCESS, FILAMENT, PRINTER }

/** Which options each kind of preset holds. */
data class PresetOptionKeys(val process: List<String>, val filament: List<String>, val printer: List<String>) {
    fun of(kind: PresetKind): List<String> = when (kind) {
        PresetKind.PROCESS -> process
        PresetKind.FILAMENT -> filament
        PresetKind.PRINTER -> printer
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
    val mode: OptionMode = OptionMode.SIMPLE,
    /** A filament override that may be left unset ("nil"), so the printer's value applies. */
    val nullable: Boolean = false,
    /** Edited as several lines, e.g. custom G-code or notes. */
    val multiline: Boolean = false,
)
