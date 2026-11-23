package com.orcamobile.core.domain.project

import com.orcamobile.core.model.BrimEar
import com.orcamobile.core.model.EmbossSource
import com.orcamobile.core.model.FeaturePick
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.LayerProfile
import com.orcamobile.core.model.LayerProfileAction
import com.orcamobile.core.model.PlateConfig
import com.orcamobile.core.model.PlateUpdate
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.VolumeKind

/** The desktop's variable layer height, brim ears and emboss, run by the engine on a loaded project. */
interface ObjectTools {
    /** Applies [action] to object [objectIndex]'s layer heights (sliced with [config]) and returns them. */
    suspend fun layerProfile(project: ProjectId, objectIndex: Int, config: PlateConfig, action: LayerProfileAction): Result<LayerProfile>

    /** The object's brim ears, as seen through [instance]. */
    suspend fun brimEars(project: ProjectId, instance: InstanceRef): Result<List<BrimEar>>

    /** Replaces the object's brim ears; returns them. */
    suspend fun setBrimEars(project: ProjectId, instance: InstanceRef, ears: List<BrimEar>): Result<List<BrimEar>>

    /** Ears at the outline's corners sharper than [maxAngleDegrees] (not kept). */
    suspend fun findBrimEars(
        project: ProjectId,
        instance: InstanceRef,
        maxAngleDegrees: Double,
        detectionMm: Double,
        radiusMm: Double,
    ): Result<List<BrimEar>>

    /** The shape's width and height in mm. */
    suspend fun embossSize(source: EmbossSource): Result<Pair<Double, Double>>

    /** On the surface at [at], as a volume of [kind]; [PlateUpdate.placed] is its index in the object. */
    suspend fun embossOnSurface(project: ProjectId, at: FeaturePick, kind: VolumeKind, source: EmbossSource): Result<PlateUpdate>

    /** As a new object lying on the bed at ([x], [y]); [PlateUpdate.placed] is its index. */
    suspend fun embossObject(project: ProjectId, source: EmbossSource, x: Double, y: Double): Result<PlateUpdate>
}
