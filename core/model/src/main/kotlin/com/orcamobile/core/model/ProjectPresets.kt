package com.orcamobile.core.model

/**
 * The presets a project file was saved with, as they can be chosen here: the
 * [selection] (each the preset the project names, or the system preset it is
 * based on), the settings it [changes] over them (serialized as in profiles),
 * and what couldn't be matched ([problems]). [selection] is null when the
 * project's printer isn't here.
 */
data class ProjectPresets(
    val selection: PresetSelection?,
    val changes: Map<String, String>,
    val problems: List<String>,
)
