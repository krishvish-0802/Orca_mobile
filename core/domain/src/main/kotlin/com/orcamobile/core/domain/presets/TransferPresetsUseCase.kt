package com.orcamobile.core.domain.presets

import com.orcamobile.core.model.UserPreset
import javax.inject.Inject

/** What an import added (under the names it was added as), and what it left out and why. */
data class PresetImport(val added: List<UserPreset>, val problems: List<String>)

/**
 * The desktop's Import configs and preset export: the user's presets in and
 * out as the desktop's own files, so they move between the phone and a computer.
 */
class TransferPresetsUseCase @Inject constructor(
    private val picker: PresetPicker,
    private val format: PresetFileFormat,
    private val documents: PresetDocuments,
) {
    /** Adds the presets in the document at [uri] to the user's (one of the same name is replaced). */
    suspend fun import(uri: String): Result<PresetImport> = runCatching {
        val vendor = checkNotNull(picker.choices.value.vendor) { "Choose a printer first: imported presets go over its maker's" }
        val found = mutableListOf<UserPreset>()
        val problems = mutableListOf<String>()
        for (path in documents.open(uri).getOrThrow()) {
            format.read(vendor, path).fold(
                { contents ->
                    found += contents.presets
                    problems += contents.problems
                },
                { error -> problems += error.message ?: error.javaClass.simpleName },
            )
        }
        val unique = found.associateBy { it.kind to it.name }.values.toList()
        if (unique.isEmpty() && problems.isEmpty()) problems += "The file holds no presets of your own."
        val added = if (unique.isEmpty()) emptyList() else picker.addUserPresets(unique).getOrThrow()
        PresetImport(added, problems)
    }

    /** Writes all the user's presets to [uri] as a .zip the desktop imports. Returns how many. */
    suspend fun exportAll(uri: String): Result<Int> = runCatching {
        val presets = picker.choices.value.userPresets
        check(presets.isNotEmpty()) { "You have no presets of your own to export yet" }
        val files = presets.associate { preset -> "${preset.kind.folder}/${safeFileName(preset.name)}.json" to format.write(preset).getOrThrow() }
        documents.saveZip(uri, files).getOrThrow()
        presets.size
    }

    private fun safeFileName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
}
