package com.orcamobile.core.engine

import android.content.Context
import com.orcamobile.core.engine.jni.NativeEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Points libslic3r at app-private directories. Safe to call more than once. */
@Singleton
class EngineEnvironment @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val initialized = AtomicBoolean(false)

    internal fun ensureInitialized() {
        if (NativeEngine.loadError != null || !initialized.compareAndSet(false, true)) return
        val root = File(context.filesDir, "engine")
        val resources = File(root, "resources").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        val temp = File(context.cacheDir, "engine").apply { mkdirs() }
        NativeEngine.nativeInitialize(resources.path, data.path, temp.path, LOG_LEVEL_WARNING)
    }

    private companion object {
        // libslic3r's set_logging_level(): 0 fatal ... 5 trace.
        const val LOG_LEVEL_WARNING = 2
    }
}
