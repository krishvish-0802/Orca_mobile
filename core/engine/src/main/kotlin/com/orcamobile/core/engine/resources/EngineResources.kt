package com.orcamobile.core.engine.resources

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the engine's resources directory (OrcaSlicer profiles, ...) in step with
 * the pack bundled in the APK. Unpacks only when the pack's checksum differs
 * from the one recorded at the last install, i.e. on first run and after an
 * app update that changed the resources.
 */
@Singleton
class EngineResources @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Where libslic3r's resources_dir() points. */
    val directory: File get() = File(context.filesDir, "engine/resources")

    /** Installs the bundled pack if needed. Blocking; call off the main thread. */
    @Synchronized
    fun ensureInstalled() {
        val bundledChecksum = readAsset(CHECKSUM_ASSET) ?: return // build without bundled resources
        val stamp = File(directory, STAMP_FILE)
        if (stamp.isFile && stamp.readText() == bundledChecksum) return

        // Unpack next to the live directory, then swap, so a crash mid-way never
        // leaves a half-written resources tree behind.
        val staging = File(directory.parentFile, "resources.staging").apply { deleteRecursively(); mkdirs() }
        context.assets.open(PACK_ASSET).use { ResourcePack.unpack(it, staging) }
        File(staging, STAMP_FILE).writeText(bundledChecksum)
        directory.deleteRecursively()
        check(staging.renameTo(directory)) { "Could not install engine resources into $directory" }
    }

    private fun readAsset(name: String): String? = try {
        context.assets.open(name).bufferedReader().use { it.readText().trim() }
    } catch (_: FileNotFoundException) {
        null
    }

    private companion object {
        const val PACK_ASSET = "engine/resources.opk"
        const val CHECKSUM_ASSET = "engine/resources.opk.sha256"
        const val STAMP_FILE = ".pack-sha256"
    }
}
