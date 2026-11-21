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
