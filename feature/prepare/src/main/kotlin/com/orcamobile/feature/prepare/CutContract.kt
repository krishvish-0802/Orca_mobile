package com.orcamobile.feature.prepare

import com.orcamobile.core.model.CutCheck
import com.orcamobile.core.model.CutConnector
import com.orcamobile.core.model.CutMode
import com.orcamobile.core.model.CutOptions
import com.orcamobile.core.model.CutPlanePoint
import com.orcamobile.core.model.CutRequest
import com.orcamobile.core.model.DovetailProfile
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.ProjectId

/** The cut tool on the selected object, as the desktop's Cut gizmo. Produced only by [CutViewModel]. */
data class CutUiState(
    val project: ProjectId? = null,
    /** The cut as it stands; null while the tool is closed. */
    val request: CutRequest? = null,
    /** The engine's checks of [request], once it made them. */
    val check: CutCheck? = null,
    /** Taps on the plane's section add connectors. */
    val addingConnectors: Boolean = false,
    /** The next connector's kind and size, and the selected one's. */
    val connector: CutConnector = CutConnector(),
    val selectedConnector: Int? = null,
    val cutting: Boolean = false,
    val error: String? = null,
) {
    val active: Boolean get() = request != null

    val canCut: Boolean get() = request != null && check?.canCut == true && !cutting
}

sealed interface CutIntent {
    /** Opens the tool on [instance] with the plane through the middle of the object. */
    data class Start(val project: ProjectId, val instance: InstanceRef) : CutIntent

    data object Close : CutIntent

    /** The plate checked the cut it shows. */
    data class Checked(val check: CutCheck) : CutIntent

    data class SetHeight(val heightMm: Double) : CutIntent

    /** A drag of the plane's handle: up by [deltaMm]. */
    data class MoveHeight(val deltaMm: Double) : CutIntent

    data class SetTilt(val xDegrees: Double, val yDegrees: Double) : CutIntent

    /** Level again, through the middle. */
    data object ResetPlane : CutIntent

    data class SetMode(val mode: CutMode) : CutIntent

    data class SetOptions(val options: CutOptions) : CutIntent

    data class SetDovetail(val profile: DovetailProfile) : CutIntent

    /** The next connector's settings; the selected connector takes them too. */
    data class SetConnector(val connector: CutConnector) : CutIntent

    data class SetAddingConnectors(val adding: Boolean) : CutIntent

    /** A tap on the plane: adds a connector there, or picks the one tapped. */
    data class TapPlane(val point: CutPlanePoint) : CutIntent

    data class SelectConnector(val index: Int?) : CutIntent

    data object RemoveSelectedConnector : CutIntent

    data object RemoveAllConnectors : CutIntent

    data object Perform : CutIntent
}

sealed interface CutEvent {
    /** The object became [pieces] objects; the plate holds [objects] now. */
    data class Cut(val objects: List<ModelObjectInfo>, val pieces: Int) : CutEvent
}
