package com.orcamobile.core.engine

import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.PresetSelection
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** [ConfigOptionCatalog] over the engine (native/bridge/src/ConfigOptions.cpp). */
@Singleton
class NativeConfigOptionCatalog @Inject constructor(
    private val environment: EngineEnvironment,
    private val dispatchers: CoroutineDispatchers,
) : ConfigOptionCatalog {

    /** Definitions do not change while the app runs. */
    private val described = java.util.concurrent.ConcurrentHashMap<String, ConfigOption>()

    override suspend fun describe(keys: List<String>): Result<List<ConfigOption>> = call {
        val missing = keys.filter { it !in described }
        if (missing.isNotEmpty()) {
            EngineJson.configOptions(NativeEngine.nativeDescribeOptions(missing.toTypedArray())).getOrThrow()
                .forEach { described[it.key] = it }
        }
        keys.mapNotNull { described[it] }
    }

    override suspend fun values(selection: PresetSelection?, keys: List<String>): Result<Map<String, String>> = call {
        EngineJson.configValues(NativeEngine.nativeConfigValues(EngineJson.encode(selection), keys.toTypedArray())).getOrThrow()
    }

    private suspend fun <T> call(query: () -> T): Result<T> = withContext(dispatchers.io) {
        runCatching {
            if (!BuildConfig.NATIVE_ENGINE_BUNDLED) throw EngineException("This build does not include the native engine.")
            NativeEngine.loadError?.let { throw EngineException(it) }
            environment.ensureInitialized()
            query()
        }
    }
}
