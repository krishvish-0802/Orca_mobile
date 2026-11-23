package com.orcamobile.core.domain.project

import com.orcamobile.core.model.AlignMode
import com.orcamobile.core.model.ArrangeOptions
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.PlateAction
import com.orcamobile.core.model.PlateConfig
import com.orcamobile.core.model.PlateUpdate
import com.orcamobile.core.model.Plates
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.TransformEdit
import com.orcamobile.core.model.TransformUpdate
import com.orcamobile.core.model.WipeTower

/**
 * The desktop's plate tools, run by the engine on a loaded project. Each
 * returns the plate's objects after the change.
 */
interface PlateEditor {
    /**
     * Packs every object of the current plate on the bed; what does not fit goes
     * beside it. With [allPlates], packs every plate's objects plate after plate,
     * adding plates for what does not fit (the desktop's Arrange all plates).
     */
    suspend fun arrange(
        project: ProjectId,
        config: PlateConfig,
        options: ArrangeOptions,
        allPlates: Boolean = false,
    ): Result<PlateUpdate>

    /** Turns [instance], or every instance of the current plate when null, to print with the least support. */
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

    /** Reads or changes the project's plates. */
    suspend fun plates(project: ProjectId, action: PlateAction): Result<Plates>

    /** The current plate's prime tower, or null when it prints none. */
    suspend fun wipeTower(project: ProjectId, config: PlateConfig): Result<WipeTower?> = Result.success(null)

    /**
     * Lines up [instances]: with the outermost of them, or with the plate's
     * edges or middle when [toPlate]. [PlateUpdate.placed] is how many moved.
     */
    suspend fun align(
        project: ProjectId,
        instances: List<InstanceRef>,
        mode: AlignMode,
        toPlate: Boolean,
        config: PlateConfig,
    ): Result<PlateUpdate>
}
