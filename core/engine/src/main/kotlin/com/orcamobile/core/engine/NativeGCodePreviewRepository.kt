package com.orcamobile.core.engine

import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.preview.GCodePreviewRepository
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.model.GCodePreviewSummary
import com.orcamobile.core.model.PreviewId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** [GCodePreviewRepository] over the bridge's GCodePreview (native/bridge/src/GCodePreview.cpp). */
@Singleton
class NativeGCodePreviewRepository @Inject constructor(
    private val environment: EngineEnvironment,
    private val registry: NativePreviewRegistry,
    private val dispatchers: CoroutineDispatchers,
) : GCodePreviewRepository {

    override suspend fun load(gcodePath: String): Result<GCodePreviewSummary> = withContext(dispatchers.io) {
        runCatching {
            if (!BuildConfig.NATIVE_ENGINE_BUNDLED) throw EngineException("This build does not include the native engine.")
            NativeEngine.loadError?.let { throw EngineException(it) }
            environment.ensureInitialized()
            val loaded = EngineJson.loadedPreview(NativeEngine.nativePreviewLoad(gcodePath)).getOrThrow()
            // The native read cannot be interrupted; if the caller went away meanwhile,
            // free the toolpaths now rather than leave them unreachable.
            if (!isActive) {
                NativeEngine.nativePreviewRelease(loaded.handle)
                throw CancellationException("Preview no longer wanted")
            }
            loaded.summary.copy(id = registry.register(loaded.handle), gcodePath = gcodePath)
        }.onFailure { if (it is CancellationException) throw it }
    }

    override fun release(id: PreviewId) {
        registry.remove(id)?.let(NativeEngine::nativePreviewRelease)
    }
}
