package com.orcamobile.core.domain.usecase

import com.orcamobile.core.domain.project.PlateConfigResolver
import com.orcamobile.core.domain.project.PlateEditor
import com.orcamobile.core.model.ArrangeOptions
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.PlateAction
import com.orcamobile.core.model.PlateUpdate
import com.orcamobile.core.model.Plates
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.TransformEdit
import com.orcamobile.core.model.TransformUpdate
import javax.inject.Inject

/**
 * Arrange, orient, copies, fill bed and delete for the current plate, and the plates themselves, with the config
 * the plate will be sliced with (presets plus the user's settings changes).
 */
class PlateToolsUseCase @Inject constructor(
    private val editor: PlateEditor,
    private val configs: PlateConfigResolver,
) {
    suspend fun arrange(project: ProjectId, presets: PresetSelection?, options: ArrangeOptions): Result<PlateUpdate> =
        editor.arrange(project, configs.configFor(presets), options)

    /** Arrange all plates: what does not fit on one plate goes on the next, new plates as needed. */
    suspend fun arrangeAllPlates(project: ProjectId, presets: PresetSelection?, options: ArrangeOptions): Result<PlateUpdate> =
        editor.arrange(project, configs.configFor(presets), options, allPlates = true)

    suspend fun plates(project: ProjectId, action: PlateAction = PlateAction.Get): Result<Plates> =
        editor.plates(project, action)

    suspend fun orient(project: ProjectId, presets: PresetSelection?, instance: InstanceRef?): Result<PlateUpdate> =
        editor.orient(project, instance, configs.configFor(presets))

    /** [count] copies of [instance], or as many as fit when null. */
    suspend fun addCopies(
        project: ProjectId,
        presets: PresetSelection?,
        instance: InstanceRef,
        count: Int?,
        options: ArrangeOptions,
    ): Result<PlateUpdate> = editor.addCopies(project, instance, count, configs.configFor(presets), options)

    /** The object manipulation panel: reads [instance]'s transform, after [edit] when given. */
    suspend fun transform(project: ProjectId, instance: InstanceRef, edit: TransformEdit? = null): Result<TransformUpdate> =
        editor.transform(project, instance, edit)

    suspend fun splitToObjects(project: ProjectId, objectIndex: Int): Result<PlateUpdate> =
        editor.splitToObjects(project, objectIndex)

    suspend fun remove(project: ProjectId, instance: InstanceRef): Result<PlateUpdate> = editor.remove(project, instance)
}
