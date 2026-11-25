package com.orcamobile.core.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.project.ProjectDocuments
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Project documents through Android's document picker; the engine writes a local file first. */
@Singleton
class ContentProjectDocuments @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
) : ProjectDocuments {

    override suspend fun scratchFile(name: String): String = withContext(dispatchers.io) {
        val dir = File(context.cacheDir, "project_save").apply { mkdirs() }
        File(dir, name).apply { delete() }.absolutePath
    }

    override suspend fun copyTo(localPath: String, uri: String): Result<Unit> = withContext(dispatchers.io) {
        runCatching {
            // "wt": replace what the document held (a project saved again).
            val out = context.contentResolver.openOutputStream(Uri.parse(uri), "wt") ?: throw IOException("Could not write there")
            out.use { stream -> File(localPath).inputStream().use { it.copyTo(stream) } }
            File(localPath).delete()
            Unit
        }
    }

    override suspend fun nameOf(uri: String): String? = withContext(dispatchers.io) {
        runCatching {
            context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
}
