package com.orcamobile.core.model

/**
 * A preset the user saved, as the desktop keeps one: the system preset it
 * [inherits] with the user's [values] over it (serialized as in profiles). It is
 * listed wherever that system preset is, under its own [name].
 */
data class UserPreset(
    val kind: PresetKind,
    val name: String,
    val inherits: String,
    val values: Map<String, String>,
)
