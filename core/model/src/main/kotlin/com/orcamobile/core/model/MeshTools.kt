package com.orcamobile.core.model

// The desktop's mesh tools on the plate. [nativeValue]s are the bridge's
// (native/bridge/include/orca_engine/MeshTools.hpp), which are OrcaSlicer's.

/** A plug stands on the lower piece and fits a hole in the upper one; a dowel is a separate pin; a snap clicks in. */
enum class CutConnectorType(val nativeValue: Int) { PLUG(0), DOWEL(1), SNAP(2) }

/** Straight sides, or narrowing toward the tip. */
enum class CutConnectorStyle(val nativeValue: Int) { PRISM(0), FRUSTUM(1) }

enum class CutConnectorShape(val nativeValue: Int) { TRIANGLE(0), SQUARE(1), HEXAGON(2), CIRCLE(3) }

/** A connector on the cut face, [u] and [v] mm from the plane's center along its own axes. */
data class CutConnector(
    val u: Double = 0.0,
    val v: Double = 0.0,
    val type: CutConnectorType = CutConnectorType.PLUG,
    val style: CutConnectorStyle = CutConnectorStyle.PRISM,
    val shape: CutConnectorShape = CutConnectorShape.CIRCLE,
    /** Across, mm (the desktop's default). */
    val sizeMm: Double = 5.0,
    /** Into the upper piece, mm. */
    val depthMm: Double = 5.0,
    /** The hole this much wider and deeper than the connector. */
    val sizeToleranceMm: Double = 0.0,
    val depthToleranceMm: Double = 0.1,
    val rotationDegrees: Double = 0.0,
) {
    /** The same connector somewhere else. */
    fun at(u: Double, v: Double) = copy(u = u, v = v)
}

/** A flat cut, or a dovetail (tongue and groove) along the plane. */
enum class CutMode(val nativeValue: Int) { PLANAR(0), DOVETAIL(1) }

/** The dovetail's profile; a depth or width of 0 lets the engine size it for the object. */
data class DovetailProfile(
    val depthMm: Double = 0.0,
    val widthMm: Double = 0.0,
    val flapsAngleDegrees: Double = 60.0,
    val angleDegrees: Double = 0.0,
    val depthToleranceMm: Double = 0.1,
    val widthToleranceMm: Double = 0.1,
    val count: Int = 1,
    val gapMm: Double = 10.0,
)

/** The plane through the instance: above the middle of its box at [heightMm] above the bed, tilted about X then Y. */
data class CutPlane(
    val heightMm: Double = 0.0,
    val tiltXDegrees: Double = 0.0,
    val tiltYDegrees: Double = 0.0,
)

/** What becomes of the pieces. */
data class CutOptions(
    val keepUpper: Boolean = true,
    val keepLower: Boolean = true,
    /** One object with both pieces as parts. */
    val asParts: Boolean = false,
    val placeOnCutUpper: Boolean = false,
    val placeOnCutLower: Boolean = false,
    val flipUpper: Boolean = false,
    val flipLower: Boolean = false,
)

data class CutRequest(
    val instance: InstanceRef,
    val plane: CutPlane = CutPlane(),
    val mode: CutMode = CutMode.PLANAR,
    val options: CutOptions = CutOptions(),
    val connectors: List<CutConnector> = emptyList(),
    val dovetail: DovetailProfile = DovetailProfile(),
) {
    /** With connectors both pieces are kept as objects, as on the desktop. */
    val hasConnectors: Boolean get() = mode == CutMode.PLANAR && connectors.isNotEmpty()
}

/** Why a connector cannot go where it is ([nativeValue] is the bridge's ConnectorProblem). */
enum class ConnectorProblem(val nativeValue: Int) {
    NONE(0),
    OUTSIDE_SECTION(1),
    OVERLAPPING(2),
    OUTSIDE_OBJECT(3),
    ;

    companion object {
        fun of(value: Int) = entries.firstOrNull { it.nativeValue == value } ?: NONE
    }
}

/** What the engine says about a cut before it is made. Points are world mm. */
data class CutCheck(
    val boxMin: List<Double>,
    val boxMax: List<Double>,
    val center: List<Double>,
    val normal: List<Double>,
    val problems: List<ConnectorProblem>,
    val canCut: Boolean,
    /** Why not, when it cannot. */
    val reason: String,
    /** The dovetail depth the engine picks for this object. */
    val dovetailDepthMm: Double,
) {
    val heightRange: ClosedFloatingPointRange<Double>
        get() = (boxMin.getOrElse(2) { 0.0 })..(boxMax.getOrElse(2) { 0.0 })
}

/** A tap on the cut plane: mm from its center, and whether that is on the object's section. */
data class CutPlanePoint(val u: Double, val v: Double, val insideSection: Boolean)
