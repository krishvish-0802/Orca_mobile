package com.orcamobile.core.engine

import com.orcamobile.core.engine.jni.NativeEngine

/** The engine's native library, for modules that call into it directly (:core:viewport). */
object EngineLibrary {
    /** True when `liborca_engine.so` is bundled and loaded. */
    val isAvailable: Boolean
        get() = BuildConfig.NATIVE_ENGINE_BUNDLED && NativeEngine.loadError == null
}
