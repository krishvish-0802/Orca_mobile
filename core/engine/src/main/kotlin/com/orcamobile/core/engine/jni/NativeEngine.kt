package com.orcamobile.core.engine.jni

/**
 * JNI surface of `liborca_engine.so` (native/bridge/jni/OrcaEngineJni.cpp).
 *
 * Kept deliberately small: calls that can fail return JSON, which
 * [com.orcamobile.core.engine.EngineJson] decodes, so changing a result shape
 * never means changing a JNI signature. Only [NativeSlicingEngine][com.orcamobile.core.engine.NativeSlicingEngine]
 * calls into this object.
 */
internal object NativeEngine {
    private const val LIBRARY = "orca_engine"

    /** Null when loaded; otherwise why the library could not be loaded. */
    val loadError: String? = try {
        System.loadLibrary(LIBRARY)
        null
    } catch (e: UnsatisfiedLinkError) {
        e.message ?: "lib$LIBRARY.so could not be loaded"
    }

    @JvmStatic external fun nativeEngineInfoJson(): String

    @JvmStatic external fun nativeInitialize(resourcesDir: String, dataDir: String, tempDir: String, logLevel: Int)

    @JvmStatic external fun nativeCreateCancelToken(): Long

    @JvmStatic external fun nativeRequestCancel(token: Long)

    @JvmStatic external fun nativeReleaseCancelToken(token: Long)

    @JvmStatic external fun nativeSlice(
        modelPath: String,
        outputPath: String,
        overrideKeys: Array<String>,
        overrideValues: Array<String>,
        listener: NativeProgressListener,
        cancelToken: Long,
    ): String
}

/** Receives slicing progress on the slicing thread. */
fun interface NativeProgressListener {
    fun onProgress(percent: Int, message: String)
}
