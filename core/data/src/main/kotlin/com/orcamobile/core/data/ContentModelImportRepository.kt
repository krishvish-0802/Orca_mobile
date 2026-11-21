package com.orcamobile.core.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.model.ModelImportRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies documents picked through the system file picker into app storage.
 * The engine reads by path and picks the loader from the extension, so the
 * file keeps its original name.
 */
@Singleton
class ContentModelImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
) : ModelImportRepository {

    override suspend fun import(uri: String): Result<String> = withContext(dispatchers.io) {
        runCatching {
            val source = Uri.parse(uri)
            val name = displayName(source) ?: source.lastPathSegment ?: "model"
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension !in SUPPORTED_EXTENSIONS) {
                throw IOException("Unsupported file type .$extension. Supported: ${SUPPORTED_EXTENSIONS.joinToString()}")
            }
            val dir = File(context.filesDir, "models").apply { mkdirs() }
            val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val target = File(dir, "${System.currentTimeMillis()}_$safeName")
            val input = context.contentResolver.openInputStream(source) ?: throw IOException("Cannot open $uri")
            input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
            target.absolutePath
        }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private companion object {
        // Formats libslic3r's Model::read_from_file loads.
        val SUPPORTED_EXTENSIONS = listOf("stl", "3mf", "obj", "step", "stp", "amf", "drc", "svg")
    }
}
