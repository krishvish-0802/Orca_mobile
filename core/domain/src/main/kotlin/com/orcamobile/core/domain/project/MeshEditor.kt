package com.orcamobile.core.domain.project

import com.orcamobile.core.model.AssemblyStep
import com.orcamobile.core.model.CutCheck
import com.orcamobile.core.model.CutRequest
import com.orcamobile.core.model.FeaturePick
import com.orcamobile.core.model.MeasureOutcome
import com.orcamobile.core.model.MeasureRequest
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

    /** The features under [MeasureRequest.picks] and what lies between the first two. */
    suspend fun measure(project: ProjectId, request: MeasureRequest): Result<MeasureOutcome>

    /**
     * One assembly step between two features: [second]'s part moves (its
     * instance, or the part within one object), except for a flip of the first.
     * [value] is degrees or mm for the steps that take one, [xyz] the wanted
     * offset for [AssemblyStep.SET_XYZ].
     */
    suspend fun assemble(
        project: ProjectId,
        first: FeaturePick,
        second: FeaturePick,
        request: MeasureRequest,
        step: AssemblyStep,
        value: Double = 0.0,
        xyz: List<Double> = listOf(0.0, 0.0, 0.0),
    ): Result<PlateUpdate>
}
