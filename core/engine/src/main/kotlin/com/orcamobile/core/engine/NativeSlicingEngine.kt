package com.orcamobile.core.engine

import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.engine.SlicingEngine
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.model.EngineStatus
import com.orcamobile.core.model.SliceEvent
import com.orcamobile.core.model.SliceRequest
import com.orcamobile.core.model.SliceResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** [SlicingEngine] backed by OrcaSlicer's libslic3r through `liborca_engine.so`. */
@Singleton
class NativeSlicingEngine @Inject constructor(
    private val environment: EngineEnvironment,
    private val dispatchers: CoroutineDispatchers,
) : SlicingEngine {

    override fun status(): EngineStatus {
        if (!BuildConfig.NATIVE_ENGINE_BUNDLED) {
            return EngineStatus.Unavailable("This build does not include the native engine.")
        }
        NativeEngine.loadError?.let { return EngineStatus.Unavailable(it) }
        return runCatching { EngineJson.engineInfo(NativeEngine.nativeEngineInfoJson()) }
            .fold({ EngineStatus.Available(it) }, { EngineStatus.Unavailable(it.message ?: "Engine error") })
    }

    override fun slice(request: SliceRequest): Flow<SliceEvent> {
        val status = status()
        if (status is EngineStatus.Unavailable) {
            return flowOf(SliceEvent.Finished(SliceResult.Failed(status.reason)))
        }
        return callbackFlow {
            environment.ensureInitialized()
            val token = NativeEngine.nativeCreateCancelToken()
            val job = launch(dispatchers.io) {
                try {
                    val keys = request.configOverrides.keys.toTypedArray()
                    val values = keys.map { request.configOverrides.getValue(it) }.toTypedArray()
                    val payload = NativeEngine.nativeSlice(
                        request.modelPath,
                        request.outputGcodePath,
                        keys,
                        values,
                        { percent, message -> trySend(SliceEvent.Progress(percent, message)) },
                        token,
                    )
                    send(SliceEvent.Finished(EngineJson.sliceResult(payload, request.outputGcodePath)))
                } finally {
                    close()
                }
            }
            // Collector cancelled (screen left, user pressed cancel): stop the
            // native slice, then free the token once the slicing call returned.
            awaitClose {
                NativeEngine.nativeRequestCancel(token)
                job.invokeOnCompletion { NativeEngine.nativeReleaseCancelToken(token) }
            }
        }.flowOn(dispatchers.default)
    }
}
