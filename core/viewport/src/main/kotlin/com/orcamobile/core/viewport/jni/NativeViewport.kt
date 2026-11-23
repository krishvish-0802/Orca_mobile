package com.orcamobile.core.viewport.jni

/**
 * JNI surface of native/viewport, registered by liborca_engine.so
 * (native/bridge/jni/ViewportJni.cpp). The GL methods must run on the GL thread.
 */
internal object NativeViewport {
    @JvmStatic external fun nativeCreate(): Long

    @JvmStatic external fun nativeDestroy(handle: Long)

    @JvmStatic external fun nativeSurfaceCreated(handle: Long)

    @JvmStatic external fun nativeSurfaceChanged(handle: Long, width: Int, height: Int)

    @JvmStatic external fun nativeDraw(handle: Long)

    /** [outline] is x0, y0, x1, y1, ... in mm. */
    @JvmStatic external fun nativeSetBed(handle: Long, outline: FloatArray, maxHeight: Float)

    @JvmStatic external fun nativeShowProject(handle: Long, projectHandle: Long)

    @JvmStatic external fun nativeClearMesh(handle: Long)

    /** The instance under a screen point as (object shl 32 or instance), or -1. */
    @JvmStatic external fun nativePick(handle: Long, x: Float, y: Float): Long

    /** The volume under a screen point as [object, instance, volume, kind], or null. */
    @JvmStatic external fun nativePickVolume(handle: Long, x: Float, y: Float): IntArray?

    /** Slides the highlighted volume of the selected instance by the bed distance between two screen points. */
    @JvmStatic external fun nativeMoveVolume(handle: Long, projectHandle: Long, x0: Float, y0: Float, x1: Float, y1: Float): Boolean

    /** Raises or lowers the highlighted volume by a vertical drag between two screen points. */
    @JvmStatic external fun nativeLiftVolume(handle: Long, projectHandle: Long, x0: Float, y0: Float, x1: Float, y1: Float): Boolean

    /** Turns the highlighted volume about a world axis (0 X, 1 Y, 2 Z). */
    @JvmStatic external fun nativeRotateVolumeAxis(handle: Long, projectHandle: Long, axis: Int, radians: Float): Boolean

    /** Grows the highlighted volume by [factor] along one of its axes (-1: all three). */
    @JvmStatic external fun nativeScaleVolume(handle: Long, projectHandle: Long, axis: Int, factor: Float): Boolean

    /** Turns the selected instance about a world axis (0 X, 1 Y, 2 Z) through its center. */
    @JvmStatic external fun nativeRotateSelectionAxis(handle: Long, projectHandle: Long, axis: Int, radians: Float): Boolean

    /** Grows the selected instance by [factor] along one of its axes (-1: all three). */
    @JvmStatic external fun nativeScaleSelection(handle: Long, projectHandle: Long, axis: Int, factor: Float): Boolean

    /** Turns the highlighted volume about the vertical, counter-clockwise seen from above. */
    @JvmStatic external fun nativeRotateVolume(handle: Long, projectHandle: Long, radians: Float): Boolean

    // Mesh tools (native/bridge/jni/MeshJni.cpp); requests are MeshToolsJson.encode.

    /**
     * Shows a cut on the plate (plane, section, connectors) and returns its checks, or `{"error"}`.
     * [showFace] hides what is above the plane, so the cut face shows.
     */
    @JvmStatic external fun nativeCutShow(handle: Long, projectHandle: Long, requestJson: String, showFace: Boolean): String

    @JvmStatic external fun nativeCutHide(handle: Long)

    /** Shows what simplifying would leave as a wireframe; `{"before","after"}` or `{"error"}`. */
    @JvmStatic external fun nativeSimplifyShow(handle: Long, projectHandle: Long, objectIndex: Int, requestJson: String): String

    /** Shows an object's outline on the bed and brim ears (`[[x,y,r]]`), [selected] (or -1) highlighted. */
    @JvmStatic external fun nativeBrimEarsShow(handle: Long, projectHandle: Long, objectIndex: Int, instanceIndex: Int, earsJson: String, selected: Int)

    /** A screen point on the bed, snapped to the object's outline within [maxDistance] mm: `{"x","y","bedX","bedY"}`. */
    @JvmStatic external fun nativeBrimEarPoint(
        handle: Long,
        projectHandle: Long,
        objectIndex: Int,
        instanceIndex: Int,
        x: Float,
        y: Float,
        maxDistance: Float,
    ): String

    /** The part surface under a screen point as a feature pick, or `{}`. */
    @JvmStatic external fun nativeMeasurePick(handle: Long, x: Float, y: Float): String

    /** Shows the picked features and the distance between them; the outcome as NativeEngine.nativeMeasure. */
    @JvmStatic external fun nativeMeasureShow(handle: Long, projectHandle: Long, requestJson: String): String

    /** Where a screen point lands on the cut plane: `{"u","v","inside"}`, or `{}`. */
    @JvmStatic external fun nativeCutPointAt(handle: Long, projectHandle: Long, requestJson: String, x: Float, y: Float): String

    /** How far (mm) a vertical drag moves a point at (ax, ay, az) up or down; NaN when it cannot tell. */
    @JvmStatic external fun nativeVerticalDrag(
        handle: Long,
        ax: Float,
        ay: Float,
        az: Float,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ): Float

    // Painting (native/bridge/jni/PaintJni.cpp).

    /** Opens a paint session on an object for a PaintKind and shows its paint; 0 on failure. */
    @JvmStatic external fun nativePaintBegin(handle: Long, projectHandle: Long, objectIndex: Int, kind: Int): Long

    /** One brush step at a screen point; [strokeStart] begins a new stroke. Whether it painted. */
    @JvmStatic external fun nativePaintAt(
        handle: Long,
        painting: Long,
        x: Float,
        y: Float,
        strokeStart: Boolean,
        state: Int,
        tool: Int,
        radiusMm: Float,
        fillAngleDegrees: Float,
        overhangAngleDegrees: Float,
    ): Boolean

    @JvmStatic external fun nativePaintClear(handle: Long, painting: Long)

    /** Writes the paint into the model; whether that changed what will be sliced. */
    @JvmStatic external fun nativePaintCommit(painting: Long): Boolean

    @JvmStatic external fun nativePaintEnd(handle: Long, painting: Long)

    /** Colours by painted state, r, g, b, a for each (state 0 first). */
    @JvmStatic external fun nativeSetPaintPalette(handle: Long, rgba: FloatArray)

    /** The prime tower: x, y, width, depth, height, brim, rotation (degrees), overlaps (0/1); empty hides it. */
    @JvmStatic external fun nativeShowWipeTower(handle: Long, tower: FloatArray)

    /** Whether a screen point is on (or within 2 mm of) volume [volume] of the instance: a text to drag. */
    @JvmStatic external fun nativeEmbossHits(handle: Long, objectIndex: Int, instanceIndex: Int, volume: Int, x: Float, y: Float): Boolean

    /** Moves embossed [volume] onto the surface under the screen point; whether it moved. */
    @JvmStatic external fun nativeEmbossDrag(
        handle: Long, project: Long, objectIndex: Int, instanceIndex: Int, volume: Int, x: Float, y: Float,
    ): Boolean

    /** Turns embossed [volume] about its normal; whether it turned. */
    @JvmStatic external fun nativeEmbossRotate(
        handle: Long, project: Long, objectIndex: Int, instanceIndex: Int, volume: Int, radians: Float,
    ): Boolean

    /** Where a screen point meets the bed, {x, y} mm, or null. */
    @JvmStatic external fun nativeBedPoint(handle: Long, x: Float, y: Float): FloatArray?

    /** RGBA per filament, filament 1 first: model parts take their filament's colour; empty for one filament. */
    @JvmStatic external fun nativeSetFilamentColours(handle: Long, rgba: FloatArray)

    /** Highlights a volume (index in its object) of the selected instance; negative for none. */
    @JvmStatic external fun nativeHighlightVolume(handle: Long, volume: Int)

    /** Highlights an instance; a negative [objectIndex] clears the selection. */
    @JvmStatic external fun nativeSetSelection(handle: Long, objectIndex: Int, instanceIndex: Int)

    /** Slides the selected instance of [projectHandle] by the bed distance between two screen points. */
    @JvmStatic external fun nativeMoveSelection(handle: Long, projectHandle: Long, x0: Float, y0: Float, x1: Float, y1: Float): Boolean

    /** Turns the selected instance about the vertical axis, counter-clockwise seen from above. */
    @JvmStatic external fun nativeRotateSelection(handle: Long, projectHandle: Long, radians: Float): Boolean

    /**
     * Lays the instance under a screen point on the side tapped there (the nearest
     * face of its convex hull). Returns it as [nativePick] does, or -1 on a miss.
     */
    @JvmStatic external fun nativeLayOnFace(handle: Long, projectHandle: Long, x: Float, y: Float): Long

    /** Shows the toolpaths of the G-code preview with native handle [previewHandle] instead of the model. */
    @JvmStatic external fun nativeShowPreview(handle: Long, previewHandle: Long)

    @JvmStatic external fun nativeClearToolpaths(handle: Long)

    /** Toolpath layers [first]..[last], 0-based and inclusive. */
    @JvmStatic external fun nativeSetLayerRange(handle: Long, first: Int, last: Int)

    /** A ToolpathColoring.nativeValue. */
    @JvmStatic external fun nativeSetColoring(handle: Long, coloring: Int)

    @JvmStatic external fun nativeOrbit(handle: Long, dx: Float, dy: Float)

    @JvmStatic external fun nativePan(handle: Long, dx: Float, dy: Float)

    @JvmStatic external fun nativeZoom(handle: Long, factor: Float, focusX: Float, focusY: Float)

    @JvmStatic external fun nativeFit(handle: Long)
}
