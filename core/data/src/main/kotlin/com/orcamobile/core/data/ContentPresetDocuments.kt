package com.orcamobile.core.data

import android.content.Context
import android.net.Uri
import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.presets.PresetDocuments
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preset documents through Android's document picker. Archives (.zip, the
 * desktop's .orca_printer / .orca_filament bundles, .3mf projects) are
 * opened here; the files in them are read by the engine.
 */
@Singleton
class ContentPresetDocuments @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
) : PresetDocuments {

    override suspend fun open(uri: String): Result<List<String>> = withContext(dispatchers.io) {
        runCatching {
            val dir = File(context.cacheDir, "preset_import").apply {
                deleteRecursively()
                mkdirs()
            }
            val bytes = openInput(Uri.parse(uri)).use { it.readBytes(MAX_BYTES) }
            if (!isZip(bytes)) {
                return@runCatching listOf(File(dir, "preset.json").apply { writeBytes(bytes) }.absolutePath)
            }
            val files = mutableListOf<String>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    if (!entry.isDirectory && isPresetEntry(entry.name) && files.size < MAX_FILES) {
                        val target = File(dir, "${files.size}.json")
                        target.outputStream().use { out -> zip.copyLimited(out) }
                        files += target.absolutePath
                    }
                }
            }
            if (files.isEmpty()) throw IOException("This archive holds no presets")
            files
        }
    }

    override suspend fun saveZip(uri: String, files: Map<String, String>): Result<Unit> = withContext(dispatchers.io) {
        runCatching {
            val out = context.contentResolver.openOutputStream(Uri.parse(uri), "wt") ?: throw IOException("Could not write there")
            ZipOutputStream(out.buffered()).use { zip ->
                for ((path, text) in files) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }
    }

    private fun openInput(uri: Uri): InputStream = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the file")

    private fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /** A preset in an archive: a .json (not the bundle's index), or a project's settings. */
    private fun isPresetEntry(name: String): Boolean {
        val file = name.substringAfterLast('/')
        return name.endsWith("project_settings.config") ||
            (file.endsWith(".json", ignoreCase = true) && file != "bundle_structure.json" && !name.startsWith("__MACOSX"))
    }

    private fun InputStream.readBytes(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        copyLimited(out, limit)
        return out.toByteArray()
    }

    /** Copies at most [limit] bytes; a larger file is refused rather than filling the phone. */
    private fun InputStream.copyLimited(out: java.io.OutputStream, limit: Long = MAX_BYTES) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) return
            total += n
            if (total > limit) throw IOException("This file is too large to be presets")
            out.write(buffer, 0, n)
        }
    }

    private companion object {
        /** A .3mf with its meshes can be large; the settings in it are small. */
        const val MAX_BYTES = 256L * 1024 * 1024
        const val MAX_FILES = 500
    }
}
