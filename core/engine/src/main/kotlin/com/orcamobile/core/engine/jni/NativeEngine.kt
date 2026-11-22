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

    /** JSON array of vendor ids, or `{"error": ...}`. */
    @JvmStatic external fun nativeListVendors(): String

    /** JSON array of printer preset names, or `{"error": ...}`. */
    @JvmStatic external fun nativeListPrinters(vendor: String): String

    /** `{"processes":[..],"filaments":[..],"defaultProcess":..,"defaultFilament":..}` or `{"error": ...}`. */
    @JvmStatic external fun nativeCompatiblePresets(vendor: String, printer: String): String

    /** `{"outline":[[x,y],..],"maxHeight":h}` or `{"error": ...}`. */
    @JvmStatic external fun nativePrinterBed(vendor: String, printer: String): String

    /** `{"handle":"<id>","objects":[..]}` or `{"error": ...}`. Release the handle with [nativeProjectRelease]. */
    @JvmStatic external fun nativeProjectLoad(path: String): String

    @JvmStatic external fun nativeProjectObjects(handle: Long): String

    @JvmStatic external fun nativeProjectCenterOn(handle: Long, x: Double, y: Double)

    @JvmStatic external fun nativeProjectRelease(handle: Long)

    /**
     * Reads an instance's transform, after the change in [editJson] when not empty
     * (see native/bridge/jni/ProjectJni.cpp): `{"transform":{..},"objects":[..]}` or `{"error": ...}`.
     */
    @JvmStatic external fun nativeInstanceTransform(handle: Long, objectIndex: Int, instanceIndex: Int, editJson: String): String

    // Object list (native/bridge/jni/ObjectJni.cpp): `{"object":{..},"objects":[..]}` or `{"error": ...}`.

    @JvmStatic external fun nativeObjectDetails(handle: Long, objectIndex: Int): String

    /** Applies one edit (EngineJson.encodeObjectEdit) and returns the object after it. */
    @JvmStatic external fun nativeObjectEdit(handle: Long, objectIndex: Int, editJson: String): String

    /**
     * Paints every face of an object's parts with [state] for a paint kind (0 supports,
     * 1 seam, 2 fuzzy skin, 3 colour) and keeps it; whether that changed what will be sliced.
     */
    @JvmStatic external fun nativePaintEverything(handle: Long, objectIndex: Int, kind: Int, state: Int): Boolean

    /** The setting keys an object (0), a part (1) or a height range (2) takes, as a JSON array. */
    @JvmStatic external fun nativeSettingKeys(scope: Int): String

    /** Takes an instance off the plate. `{"objects":[..]}` after it, or `{"error": ...}`. */
    @JvmStatic external fun nativeProjectRemove(handle: Long, objectIndex: Int, instanceIndex: Int): String

    // Plate tools (native/bridge/jni/PlateJni.cpp). Each takes the slice's presets
    // and overrides, and returns `{"placed":n,"notPlaced":n,"objects":[..]}` or `{"error": ...}`.

    @JvmStatic external fun nativePlateArrange(
        handle: Long,
        presetSelectionJson: String,
        overrideKeys: Array<String>,
        overrideValues: Array<String>,
        optionsJson: String,
    ): String

    /** [objectIndex] -1: every instance. */
    @JvmStatic external fun nativePlateOrient(
        handle: Long,
        objectIndex: Int,
        instanceIndex: Int,
        presetSelectionJson: String,
        overrideKeys: Array<String>,
        overrideValues: Array<String>,
    ): String

    /** [count] -1: as many as fit (fill bed). */
    @JvmStatic external fun nativePlateAddCopies(
        handle: Long,
        objectIndex: Int,
        instanceIndex: Int,
        count: Int,
        presetSelectionJson: String,
        overrideKeys: Array<String>,
        overrideValues: Array<String>,
        optionsJson: String,
    ): String

    /**
     * Reads a G-code file for the preview (blocking, seconds for a large file):
     * `{"handle":"<id>","layerZs":[..],"printTimeSeconds":..,"moveCount":..,"filamentColors":[..]}`
     * or `{"error": ...}`. Release the handle with [nativePreviewRelease].
     */
    @JvmStatic external fun nativePreviewLoad(gcodePath: String): String

    @JvmStatic external fun nativePreviewRelease(handle: Long)

    /** `{"options":[...]}`: OrcaSlicer's definitions of [keys]. */
    @JvmStatic external fun nativeDescribeOptions(keys: Array<String>): String

    /** `{"process":[..],"filament":[..],"printer":[..]}`: the options each kind of preset holds. */
    @JvmStatic external fun nativePresetOptionKeys(): String

    /** `{"values":{...}}` of [keys] for a preset selection, or the engine defaults for an empty one. */
    @JvmStatic external fun nativeConfigValues(presetSelectionJson: String, keys: Array<String>): String

    @JvmStatic external fun nativeCreateCancelToken(): Long

    @JvmStatic external fun nativeRequestCancel(token: Long)

    @JvmStatic external fun nativeReleaseCancelToken(token: Long)

    /** [presetSelectionJson] is `{"vendor","printer","process","filaments":[..]}`, or empty for engine defaults. */
    @JvmStatic external fun nativeSlice(
        modelPath: String,
        outputPath: String,
        presetSelectionJson: String,
        overrideKeys: Array<String>,
        overrideValues: Array<String>,
        listener: NativeProgressListener,
        cancelToken: Long,
    ): String

    /** Like [nativeSlice], for a loaded project sliced as placed. */
    @JvmStatic external fun nativeSliceProject(
        projectHandle: Long,
        outputPath: String,
        presetSelectionJson: String,
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
