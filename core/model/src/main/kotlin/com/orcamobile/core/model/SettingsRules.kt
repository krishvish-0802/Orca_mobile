package com.orcamobile.core.model

/** Where settings are edited, for the rules between them ([nativeValue] is the bridge's SettingsScope). */
enum class RulesScope(val nativeValue: Int) {
    /** The presets: process, filament and printer settings. */
    GLOBAL(0),

    /** An object, a part or a height range, over the presets. */
    OBJECT(1),

    /** A plate's own settings. */
    PLATE(2),
}

/** Whether a setting shows and can be changed, and why not. */
data class OptionRuleState(val visible: Boolean = true, val enabled: Boolean = true, val reason: String = "")

/** One way out of a conflict: settings to change, serialized as in profiles. */
data class SettingsFix(val label: String, val changes: Map<String, String>)

/** A conflict between settings the desktop asks about; the first fix is its recommendation. */
data class SettingsCheck(val id: String, val message: String, val fixes: List<SettingsFix>)

/**
 * The desktop's rules applied to a set of settings: which are hidden or greyed
 * out (others show and can be changed), names some take for these values,
 * values the desktop sets on its own (with [notices] saying why), and conflicts
 * to resolve.
 */
data class SettingsReview(
    val options: Map<String, OptionRuleState> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
    val changes: Map<String, String> = emptyMap(),
    val notices: List<String> = emptyList(),
    val checks: List<SettingsCheck> = emptyList(),
) {
    fun stateOf(key: String): OptionRuleState = options[key] ?: SHOWN

    companion object {
        private val SHOWN = OptionRuleState()
        val NONE = SettingsReview()
    }
}
