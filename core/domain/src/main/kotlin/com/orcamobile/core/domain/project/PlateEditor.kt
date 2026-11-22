package com.orcamobile.core.domain.project

import com.orcamobile.core.model.ArrangeOptions
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.PlateConfig
import com.orcamobile.core.model.PlateUpdate
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.TransformEdit
import com.orcamobile.core.model.TransformUpdate

/**
 * The desktop's plate tools, run by the engine on a loaded project. Each
 * returns the plate's objects after the change.
 */
interface PlateEditor {
    /** Packs every object on the bed. What does not fit goes beside it. */
    suspend fun arrange(project: ProjectId, config: PlateConfig, options: ArrangeOptions): Result<PlateUpdate>

    /** Turns [instance], or every instance when null, to print with the least support. */
    suspend fun orient(project: ProjectId, instance: InstanceRef?, config: PlateConfig): Result<PlateUpdate>

    /**
     * Adds [count] copies of [instance] in the free space of the bed, or as many
     * as fit when [count] is null (fill bed). Other objects stay where they are.
     */
    suspend fun addCopies(
        project: ProjectId,
        instance: InstanceRef,
        count: Int?,
        config: PlateConfig,
        options: ArrangeOptions,
    ): Result<PlateUpdate>

    /** Reads [instance]'s transform, after applying [edit] when given. */
    suspend fun transform(project: ProjectId, instance: InstanceRef, edit: TransformEdit?): Result<TransformUpdate>

    /**
     * Splits an object into one object per separate piece (per part, for an object
     * with several), where they lie. [PlateUpdate.placed] is how many it made.
     */
    suspend fun splitToObjects(project: ProjectId, objectIndex: Int): Result<PlateUpdate>

    /** Takes [instance] off the plate, and its object with its last instance. */
    suspend fun remove(project: ProjectId, instance: InstanceRef): Result<PlateUpdate>
}
