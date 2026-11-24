package com.orcamobile.core.engine

import com.orcamobile.core.engine.jni.NativeEngine

/**
 * The relief a texture bake would make, computed by the engine for the plate
 * view (:core:viewport) to show before anything is baked. A preview is a native
 * handle: show it on a viewport, then [free] it.
 */
object TexturePreviews {
    /** A token a running [compute] can be [stop]ped by from another thread; [release] it after. */
    fun newToken(): Long = NativeEngine.nativeCreateCancelToken()

    fun stop(token: Long) = NativeEngine.nativeRequestCancel(token)

    fun release(token: Long) = NativeEngine.nativeReleaseCancelToken(token)

    /**
     * Blocking: run it off the UI and GL threads. The preview of object
     * [objectIndex] of the loaded project [projectHandle], or 0 when [token]
     * stopped it (a newer one is wanted) or it failed.
     */
    fun compute(projectHandle: Long, objectIndex: Int, token: Long): Long =
        NativeEngine.nativeTexturePreviewCompute(projectHandle, objectIndex, token)

    fun triangles(preview: Long): Long = NativeEngine.nativeTexturePreviewTriangles(preview)

    fun free(preview: Long) = NativeEngine.nativeTexturePreviewFree(preview)
}
