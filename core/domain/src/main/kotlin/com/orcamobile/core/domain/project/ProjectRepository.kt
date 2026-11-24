package com.orcamobile.core.domain.project

import com.orcamobile.core.model.HistoryAction
import com.orcamobile.core.model.HistoryState
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.ProjectSummary

/** Models loaded into the engine. A loaded project holds memory until [release]. */
interface ProjectRepository {
    suspend fun load(path: String): Result<ProjectSummary>

    /** Places the project's objects together around (x, y) on the bed. */
    suspend fun centerOn(id: ProjectId, x: Double, y: Double)

    /**
     * The filaments changed: filament [removed] (0-based) was taken away, when
     * not null, and [count] are left. Objects, parts and painting on a filament
     * that is gone fall back to the first; later ones move down.
     */
    suspend fun filamentsChanged(id: ProjectId, removed: Int?, count: Int) = Unit

    /**
     * The project's undo history: [HistoryAction.RECORD] keeps it as it is now
     * (after each change, and once when opened), UNDO goes back a step and REDO
     * forward. Steps cover its objects, parts, paint, object settings and plates.
     */
    suspend fun history(id: ProjectId, action: HistoryAction): Result<HistoryState> =
        Result.success(HistoryState(emptyList(), changed = false, canUndo = false, canRedo = false))

    fun release(id: ProjectId)
}
