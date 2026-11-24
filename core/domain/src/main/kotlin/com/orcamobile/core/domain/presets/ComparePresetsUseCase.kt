package com.orcamobile.core.domain.presets

import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.PresetSelection
import javax.inject.Inject

/** A setting two presets give different values (serialized as in profiles). */
data class PresetDifference(val option: ConfigOption, val first: String, val second: String)

/**
 * The desktop's Compare presets: the settings of one kind that two presets
 * set differently, each preset taken whole (the user's with what it
 * inherits), over the rest of the current selection.
 */
class ComparePresetsUseCase @Inject constructor(
    private val picker: PresetPicker,
    private val catalog: ConfigOptionCatalog,
) {
    suspend operator fun invoke(kind: PresetKind, first: String, second: String): Result<List<PresetDifference>> = runCatching {
        val base = checkNotNull(picker.choices.value.selection) { "Choose a printer, process and filament first" }
        val keys = catalog.presetOptionKeys().getOrThrow().of(kind)
        val a = catalog.values(base.with(kind, first), keys).getOrThrow()
        val b = catalog.values(base.with(kind, second), keys).getOrThrow()
        val differing = keys.filter { a[it] != b[it] }
        val options = catalog.describe(differing).getOrThrow().associateBy { it.key }
        differing.mapNotNull { key -> options[key]?.let { PresetDifference(it, a[key].orEmpty(), b[key].orEmpty()) } }
    }

    /** This selection with [name] as its preset of [kind] (one filament, for a filament). */
    private fun PresetSelection.with(kind: PresetKind, name: String) = when (kind) {
        PresetKind.PRINTER -> copy(printer = name)
        PresetKind.PROCESS -> copy(process = name)
        PresetKind.FILAMENT -> copy(filaments = listOf(name), filamentColours = emptyList())
    }
}
