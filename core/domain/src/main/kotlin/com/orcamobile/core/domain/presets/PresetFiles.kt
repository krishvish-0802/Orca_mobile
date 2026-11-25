package com.orcamobile.core.domain.presets

import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.UserPreset

/** The user presets a preset file holds, and why any were left out. */
data class PresetFileContents(val presets: List<UserPreset>, val problems: List<String>)

/** The desktop's preset files, read and written by its own code (the engine). */
interface PresetFileFormat {
    /**
     * The user presets in the preset file at [path] (a preset .json, or a
     * project's settings), each over the system preset of [vendor] it inherits.
     */
    suspend fun read(vendor: String, path: String): Result<PresetFileContents>

    /** [preset] as the desktop saves a user preset: JSON its Import configs takes. */
    suspend fun write(preset: UserPreset): Result<String>
}

/** Documents the user picks: read into preset files, or written as a bundle of them. */
interface PresetDocuments {
    /**
     * Local copies of the preset files in the document at [uri]: the document
     * itself (a .json), the .json files in a .zip or a desktop bundle
     * (.orca_printer, .orca_filament), or a .3mf project's settings.
     */
    suspend fun open(uri: String): Result<List<String>>

    /** Writes [files] (path in the archive to its text) to [uri] as a .zip. */
    suspend fun saveZip(uri: String, files: Map<String, String>): Result<Unit>
}

/** Where a preset of [this] kind goes in a bundle, as in the desktop's user folder. */
val PresetKind.folder: String
    get() = when (this) {
        PresetKind.PRINTER -> "machine"
        PresetKind.PROCESS -> "process"
        PresetKind.FILAMENT -> "filament"
    }
