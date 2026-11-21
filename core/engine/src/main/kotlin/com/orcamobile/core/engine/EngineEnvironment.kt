package com.orcamobile.core.engine

import android.content.Context
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.engine.resources.EngineResources
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prepares the native engine for use: installs its bundled resources and points
 * libslic3r at app-private directories. Idempotent and thread-safe; the first
 * call does the work (unpacking profiles takes about a second), so call it off
 * the main thread.
 */
@Singleton
class EngineEnvironment @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resources: EngineResources,
) {
    @Volatile
    private var initialized = false

    internal fun ensureInitialized() {
        if (initialized || NativeEngine.loadError != null) return
        synchronized(this) {
            if (initialized) return
            resources.ensureInstalled()
            val root = File(context.filesDir, "engine")
            val data = File(root, "data").apply { mkdirs() }
            val temp = File(context.cacheDir, "engine").apply { mkdirs() }
            resources.directory.mkdirs()
            NativeEngine.nativeInitialize(resources.directory.path, data.path, temp.path, LOG_LEVEL_WARNING)
            initialized = true
        }
    }

    private companion object {
        // libslic3r's set_logging_level(): 0 fatal ... 5 trace.
        const val LOG_LEVEL_WARNING = 2
    }
}
