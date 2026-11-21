package com.orcamobile.core.viewport

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import com.orcamobile.core.viewport.jni.NativeViewport
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/**
 * The GL surface the native viewport draws into. Renders only when something
 * changed (a gesture, a new scene). Gestures are handled by the Compose layer
 * above it (see PlateViewport).
 *
 * The native viewport exists while the view is attached: it is created on
 * attach and destroyed on detach, after GLSurfaceView has stopped its render
 * thread, so no frame can touch a destroyed viewport.
 */
@SuppressLint("ViewConstructor")
internal class PlateSurfaceView(context: Context) : GLSurfaceView(context) {

    /** Native viewport handle; 0 while detached. Read on the GL thread. */
    @Volatile
    private var handle = 0L

    private var bed: FloatArray? = null
    private var bedHeight = 0f
    private var projectHandle = 0L

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(Renderer())
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onAttachedToWindow() {
        if (handle == 0L) {
            handle = NativeViewport.nativeCreate()
            bed?.let { NativeViewport.nativeSetBed(handle, it, bedHeight) }
            if (projectHandle != 0L) showProjectOnGlThread(projectHandle)
        }
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow() // stops and joins the GL thread
        if (handle != 0L) {
            NativeViewport.nativeDestroy(handle)
            handle = 0L
        }
    }

    fun setBed(outline: FloatArray?, maxHeight: Float) {
        if (outline.contentEquals(bed) && maxHeight == bedHeight) return
        bed = outline
        bedHeight = maxHeight
        if (handle != 0L && outline != null) {
            NativeViewport.nativeSetBed(handle, outline, maxHeight)
            requestRender()
        }
    }

    /** Shows the project with native handle [project], or clears the plate for 0. */
    fun setProject(project: Long) {
        if (project == projectHandle) return
        projectHandle = project
        if (handle != 0L) showProjectOnGlThread(project)
    }

    fun orbit(dx: Float, dy: Float) = gesture { NativeViewport.nativeOrbit(it, dx, dy) }

    fun pan(dx: Float, dy: Float) = gesture { NativeViewport.nativePan(it, dx, dy) }

    fun zoom(factor: Float, focusX: Float, focusY: Float) = gesture { NativeViewport.nativeZoom(it, factor, focusX, focusY) }

    fun fit() = gesture { NativeViewport.nativeFit(it) }

    private inline fun gesture(action: (Long) -> Unit) {
        val h = handle
        if (h == 0L) return
        action(h)
        requestRender()
    }

    // Building the render mesh walks every triangle; keep it off the UI thread.
    private fun showProjectOnGlThread(project: Long) {
        queueEvent {
            val h = handle
            if (h == 0L) return@queueEvent
            if (project == 0L) NativeViewport.nativeClearMesh(h) else NativeViewport.nativeShowProject(h, project)
        }
        requestRender()
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            handle.takeIf { it != 0L }?.let(NativeViewport::nativeSurfaceCreated)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            handle.takeIf { it != 0L }?.let { NativeViewport.nativeSurfaceChanged(it, width, height) }
        }

        override fun onDrawFrame(gl: GL10?) {
            handle.takeIf { it != 0L }?.let(NativeViewport::nativeDraw)
        }
    }

    /** RGBA8 + depth 24 with 4x MSAA when available, otherwise without. */
    private class MultisampleConfigChooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig =
            choose(egl, display, samples = 4) ?: choose(egl, display, samples = 0)
                ?: error("No EGL config with RGBA8 and a depth buffer")

        private fun choose(egl: EGL10, display: EGLDisplay, samples: Int): EGLConfig? {
            val attributes = intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 24,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
                EGL10.EGL_SAMPLES, samples,
                EGL10.EGL_NONE,
            )
            val count = IntArray(1)
            val configs = arrayOfNulls<EGLConfig>(1)
            return if (egl.eglChooseConfig(display, attributes, configs, 1, count) && count[0] > 0) configs[0] else null
        }

        private companion object {
            const val EGL_OPENGL_ES3_BIT = 0x40
        }
    }
}
