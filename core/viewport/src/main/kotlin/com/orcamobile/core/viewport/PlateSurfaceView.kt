package com.orcamobile.core.viewport

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.PaintBrush
import com.orcamobile.core.model.PaintKind
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
    private var projectRevision = 0
    private var previewHandle = 0L
    private var layerRange: IntRange? = null
    private var coloring = 0
    private var selection: InstanceRef? = null

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
            if (previewHandle != 0L) applyPreview()
            selection?.let { NativeViewport.nativeSetSelection(handle, it.objectIndex, it.instanceIndex) }
            cutJson?.let { json -> if (projectHandle != 0L) NativeViewport.nativeCutShow(handle, projectHandle, json, cutShowsFace) }
        }
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow() // stops and joins the GL thread
        stopPainting()
        synchronized(toolLock) {
            if (handle != 0L) {
                NativeViewport.nativeDestroy(handle)
                handle = 0L
            }
        }
    }

    /** Guards the viewport against mesh-tool calls from other threads while it is destroyed. */
    private val toolLock = Any()
    private var cutJson: String? = null
    private var cutShowsFace = false

    /**
     * Shows the cut [requestJson] (MeshToolsJson.encode) on the plate, or hides
     * it for null. Returns the engine's checks as JSON, or null when nothing is
     * shown. May run off the main thread.
     */
    fun showCut(requestJson: String?, showFace: Boolean = false): String? = synchronized(toolLock) {
        cutJson = requestJson
        cutShowsFace = showFace
        val h = handle
        val project = projectHandle
        if (h == 0L) return null
        val checks = if (requestJson == null || project == 0L) {
            NativeViewport.nativeCutHide(h)
            null
        } else {
            NativeViewport.nativeCutShow(h, project, requestJson, showFace)
        }
        requestRender()
        checks
    }

    /** Shows what simplifying would leave on object [objectIndex] as a wireframe; its counts as JSON. */
    fun showSimplify(objectIndex: Int, requestJson: String): String? = synchronized(toolLock) {
        cutJson = null
        val h = handle
        val project = projectHandle
        if (h == 0L || project == 0L) return null
        val counts = NativeViewport.nativeSimplifyShow(h, project, objectIndex, requestJson)
        requestRender()
        counts
    }

    /** Shows the picked features and the distance between them; the outcome as JSON. */
    fun showMeasure(requestJson: String): String? = synchronized(toolLock) {
        cutJson = null
        val h = handle
        val project = projectHandle
        if (h == 0L || project == 0L) return null
        val outcome = NativeViewport.nativeMeasureShow(h, project, requestJson)
        requestRender()
        outcome
    }

    /** Shows an object's outline on the bed and its brim ears (`[[x,y,r]]`), [selected] highlighted. */
    fun showBrimEars(objectIndex: Int, instanceIndex: Int, earsJson: String, selected: Int): Boolean = synchronized(toolLock) {
        cutJson = null
        val h = handle
        val project = projectHandle
        if (h == 0L || project == 0L) return false
        NativeViewport.nativeBrimEarsShow(h, project, objectIndex, instanceIndex, earsJson, selected)
        requestRender()
        true
    }

    /** A screen point on the bed, snapped to the object's outline within [maxDistance] mm (JSON). */
    fun brimEarPoint(objectIndex: Int, instanceIndex: Int, x: Float, y: Float, maxDistance: Float): String? = synchronized(toolLock) {
        val h = handle
        val project = projectHandle
        if (h == 0L || project == 0L) return null
        NativeViewport.nativeBrimEarPoint(h, project, objectIndex, instanceIndex, x, y, maxDistance)
    }

    /** The part surface under a screen point, as a feature pick (JSON, `{}` on a miss). */
    fun measurePick(x: Float, y: Float): String? {
        val h = handle
        return if (h == 0L) null else NativeViewport.nativeMeasurePick(h, x, y)
    }

    /** Where a screen point lands on the shown cut's plane, as JSON (`{}` on a miss). */
    fun cutPointAt(x: Float, y: Float): String? = synchronized(toolLock) {
        val json = cutJson ?: return null
        val h = handle
        val project = projectHandle
        if (h == 0L || project == 0L) return null
        NativeViewport.nativeCutPointAt(h, project, json, x, y)
    }

    /** How far (mm) a vertical drag between two screen points moves [anchor] (world) up or down. */
    fun verticalDrag(anchor: List<Double>, x0: Float, y0: Float, x1: Float, y1: Float): Float? {
        val h = handle
        if (h == 0L || anchor.size < 3) return null
        return NativeViewport.nativeVerticalDrag(h, anchor[0].toFloat(), anchor[1].toFloat(), anchor[2].toFloat(), x0, y0, x1, y1)
            .takeIf { it.isFinite() }
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

    /**
     * Shows the project with native handle [project], or clears the plate for 0.
     * A new [revision] redraws it after the engine moved its objects.
     */
    fun setProject(project: Long, revision: Int = 0) {
        if (project == projectHandle && revision == projectRevision) return
        projectHandle = project
        projectRevision = revision
        if (handle != 0L) showProjectOnGlThread(project)
    }

    /** Shows the toolpaths of the preview with native handle [preview] instead of the model; 0 hides them. */
    fun setPreview(preview: Long, layers: IntRange?, coloring: Int) {
        val previewChanged = preview != previewHandle
        if (!previewChanged && layers == layerRange && coloring == this.coloring) return
        previewHandle = preview
        layerRange = layers
        this.coloring = coloring
        if (handle != 0L) applyPreview(showToolpaths = previewChanged)
    }

    private fun applyPreview(showToolpaths: Boolean = true) {
        val h = handle
        if (showToolpaths) {
            if (previewHandle == 0L) NativeViewport.nativeClearToolpaths(h) else NativeViewport.nativeShowPreview(h, previewHandle)
        }
        NativeViewport.nativeSetColoring(h, coloring)
        layerRange?.let { NativeViewport.nativeSetLayerRange(h, it.first, it.last) }
        requestRender()
    }

    /** The instance under a screen point, if any. */
    fun pick(x: Float, y: Float): InstanceRef? {
        val h = handle
        if (h == 0L) return null
        val packed = NativeViewport.nativePick(h, x, y)
        if (packed < 0) return null
        return InstanceRef((packed ushr 32).toInt(), (packed and 0xFFFFFFFFL).toInt())
    }

    /** Lays the instance under the point on the side tapped; the instance, or null on a miss. */
    fun layOnFace(x: Float, y: Float): InstanceRef? {
        val h = handle
        val project = projectHandle
        if (h == 0L || project == 0L) return null
        val packed = NativeViewport.nativeLayOnFace(h, project, x, y)
        if (packed < 0) return null
        requestRender()
        return InstanceRef((packed ushr 32).toInt(), (packed and 0xFFFFFFFFL).toInt())
    }

    private var highlightedVolume: Int? = null
    private var painting = 0L
    private var paintTarget: Triple<Long, Int, PaintKind>? = null

    /**
     * Paints [kind] on object [objectIndex] of the shown project from now on,
     * coloured by [palette] (r, g, b, a per state); null stops painting.
     */
    fun setPainting(objectIndex: Int?, kind: PaintKind?, palette: FloatArray) {
        val h = handle
        val target = if (objectIndex != null && kind != null && projectHandle != 0L) Triple(projectHandle, objectIndex, kind) else null
        if (target == paintTarget) {
            if (h != 0L && target != null) NativeViewport.nativeSetPaintPalette(h, palette)
            return
        }
        stopPainting()
        if (h == 0L || target == null) return
        NativeViewport.nativeSetPaintPalette(h, palette)
        painting = NativeViewport.nativePaintBegin(h, target.first, target.second, target.third.nativeValue)
        if (painting != 0L) paintTarget = target
        requestRender()
    }

    fun paintAt(x: Float, y: Float, strokeStart: Boolean, brush: PaintBrush): Boolean {
        val h = handle
        if (h == 0L || painting == 0L) return false
        return NativeViewport.nativePaintAt(
            h, painting, x, y, strokeStart, brush.state, brush.tool.nativeValue, brush.radiusMm,
            brush.fillAngleDegrees, brush.overhangAngleDegrees ?: 0f,
        ).also { if (it) requestRender() }
    }

    val isPainting: Boolean get() = painting != 0L

    /** Erases the paint of this kind and keeps that; whether it changed what will be sliced. */
    fun clearPaintAndCommit(): Boolean {
        clearPaint()
        return commitPaint()
    }

    /** Keeps the paint in the model; whether it changed what will be sliced. */
    fun commitPaint(): Boolean = painting != 0L && NativeViewport.nativePaintCommit(painting)

    fun clearPaint() {
        val h = handle
        if (h == 0L || painting == 0L) return
        NativeViewport.nativePaintClear(h, painting)
        requestRender()
    }

    private fun stopPainting() {
        if (painting != 0L) {
            NativeViewport.nativePaintCommit(painting)
            NativeViewport.nativePaintEnd(handle, painting)
            requestRender()
        }
        painting = 0L
        paintTarget = null
    }

    /** The volume under a point: its instance, its index in the object, and whether it is a part. */
    fun pickVolume(x: Float, y: Float): VolumeHit? {
        val h = handle
        if (h == 0L) return null
        val hit = NativeViewport.nativePickVolume(h, x, y) ?: return null
        return VolumeHit(InstanceRef(hit[0], hit[1]), hit[2], isPart = hit[3] == 0)
    }

    fun moveVolume(x0: Float, y0: Float, x1: Float, y1: Float): Boolean =
        volumeEdit { h, project -> NativeViewport.nativeMoveVolume(h, project, x0, y0, x1, y1) }

    fun liftVolume(x0: Float, y0: Float, x1: Float, y1: Float): Boolean =
        volumeEdit { h, project -> NativeViewport.nativeLiftVolume(h, project, x0, y0, x1, y1) }

    fun rotateVolume(radians: Float): Boolean = volumeEdit { h, project -> NativeViewport.nativeRotateVolume(h, project, radians) }

    /** Turns the picked volume, or the selected object without one, about a world axis (0 X, 1 Y, 2 Z). */
    fun rotateAbout(axis: Int, radians: Float): Boolean =
        if (highlightedVolume != null) {
            volumeEdit { h, project -> NativeViewport.nativeRotateVolumeAxis(h, project, axis, radians) }
        } else {
            edit { h, project -> NativeViewport.nativeRotateSelectionAxis(h, project, axis, radians) }
        }

    /** Grows the picked volume, or the selected object without one, along an axis (-1: all three). */
    fun scaleAlong(axis: Int, factor: Float): Boolean =
        if (highlightedVolume != null) {
            volumeEdit { h, project -> NativeViewport.nativeScaleVolume(h, project, axis, factor) }
        } else {
            edit { h, project -> NativeViewport.nativeScaleSelection(h, project, axis, factor) }
        }

    private inline fun volumeEdit(action: (Long, Long) -> Boolean): Boolean {
        if (highlightedVolume == null) return false
        return edit(action)
    }

    fun setHighlightedVolume(volume: Int?) {
        if (volume == highlightedVolume) return
        highlightedVolume = volume
        gesture { NativeViewport.nativeHighlightVolume(it, volume ?: -1) }
    }

    fun setSelection(ref: InstanceRef?) {
        if (ref == selection) return
        selection = ref
        gesture { NativeViewport.nativeSetSelection(it, ref?.objectIndex ?: -1, ref?.instanceIndex ?: 0) }
    }

    /** Drags the selected instance with a finger moving between two screen points. */
    fun moveSelection(x0: Float, y0: Float, x1: Float, y1: Float): Boolean =
        edit { h, project -> NativeViewport.nativeMoveSelection(h, project, x0, y0, x1, y1) }

    fun rotateSelection(radians: Float): Boolean = edit { h, project -> NativeViewport.nativeRotateSelection(h, project, radians) }

    private inline fun edit(action: (Long, Long) -> Boolean): Boolean {
        val h = handle
        val project = projectHandle
        if (h == 0L || project == 0L || selection == null) return false
        return action(h, project).also { if (it) requestRender() }
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

/** A volume hit on the plate: [volume] is its index in the instance's object. */
data class VolumeHit(val instance: InstanceRef, val volume: Int, val isPart: Boolean)

/** What the handles at the plate's edge do (the desktop's move, rotate and scale gizmos). */
enum class HandleMode { MOVE, ROTATE, SCALE }
