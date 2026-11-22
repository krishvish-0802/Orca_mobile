package com.orcamobile.core.domain.project

import com.orcamobile.core.model.CutCheck
import com.orcamobile.core.model.CutRequest
import com.orcamobile.core.model.PlateUpdate
import com.orcamobile.core.model.ProjectId

/** The desktop's mesh tools, run by the engine on a loaded project. */
interface MeshEditor {
    /** Whether [request] can be cut, and why not. */
    suspend fun checkCut(project: ProjectId, request: CutRequest): Result<CutCheck>

    /**
     * Cuts as the desktop's Cut tool: the pieces replace the object where it was
     * and come last. [PlateUpdate.placed] is how many there are.
     */
    suspend fun cut(project: ProjectId, request: CutRequest): Result<PlateUpdate>
}
