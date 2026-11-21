package com.orcamobile.core.domain.project

import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.ProjectSummary

/** Models loaded into the engine. A loaded project holds memory until [release]. */
interface ProjectRepository {
    suspend fun load(path: String): Result<ProjectSummary>

    /** Places the project's objects together around (x, y) on the bed. */
    suspend fun centerOn(id: ProjectId, x: Double, y: Double)

    fun release(id: ProjectId)
}
