package com.orcamobile.core.data

import android.content.Context
import com.orcamobile.core.domain.model.WorkspaceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps generated G-code in app-private storage, one file per slice. */
@Singleton
class FileWorkspaceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : WorkspaceRepository {

    override fun newGcodePath(baseName: String): String {
        val dir = File(context.filesDir, "gcode").apply { mkdirs() }
        val safeName = baseName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "${safeName}_${System.currentTimeMillis()}.gcode").absolutePath
    }
}
