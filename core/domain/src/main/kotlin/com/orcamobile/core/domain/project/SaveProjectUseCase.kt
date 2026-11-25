package com.orcamobile.core.domain.project

import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.ProjectId
import javax.inject.Inject

/** Documents the user picks to keep projects in. */
interface ProjectDocuments {
    /** A local file named [name] to write a project to before it goes to a document. */
    suspend fun scratchFile(name: String): String

    /** Copies the file at [localPath] into the document at [uri], replacing what it held. */
    suspend fun copyTo(localPath: String, uri: String): Result<Unit>

    /** The document's name as the user sees it ("cube.3mf"), when known. */
    suspend fun nameOf(uri: String): String?
}

/**
 * The desktop's Save project: the plate as a .3mf the desktop opens, with its
 * plates, objects, edits and paint, and the presets and settings changes in use.
 */
class SaveProjectUseCase @Inject constructor(
    private val projects: ProjectRepository,
    private val configs: PlateConfigResolver,
    private val documents: ProjectDocuments,
) {
    suspend operator fun invoke(project: ProjectId, presets: PresetSelection?, uri: String): Result<Unit> = runCatching {
        val file = documents.scratchFile("project.3mf")
        projects.save(project, file, configs.configFor(presets)).getOrThrow()
        documents.copyTo(file, uri).getOrThrow()
    }
}
