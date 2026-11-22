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

    /** Turns the highlighted volume about the vertical, counter-clockwise seen from above. */
    @JvmStatic external fun nativeRotateVolume(handle: Long, projectHandle: Long, radians: Float): Boolean

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
