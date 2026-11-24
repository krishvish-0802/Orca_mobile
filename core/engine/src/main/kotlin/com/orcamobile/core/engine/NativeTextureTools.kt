package com.orcamobile.core.engine

import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.project.TextureTools
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.engine.jni.NativeProgressListener
import com.orcamobile.core.model.LibraryTexture
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.TextureBake
import com.orcamobile.core.model.TextureLayerSettings
import com.orcamobile.core.model.TextureStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** [TextureTools] over the bridge (native/bridge/src/TextureTool.cpp). */
@Singleton
class NativeTextureTools @Inject constructor(
    private val environment: EngineEnvironment,
    private val registry: NativeProjectRegistry,
    private val dispatchers: CoroutineDispatchers,
) : TextureTools {

    override suspend fun stack(project: ProjectId, objectIndex: Int) = edit(project, objectIndex, TextureJson.request("get"))

    override suspend fun addLayer(project: ProjectId, objectIndex: Int, imagePath: String, name: String): Result<Pair<TextureStack, Int>> =
        call(project) { handle ->
            val (stack, slot) = TextureJson.stack(
                NativeEngine.nativeTextureStack(handle, objectIndex, TextureJson.request("add") { put("path", imagePath); put("name", name) }),
            ).getOrThrow()
            stack to (slot ?: throw EngineException("The engine did not say which layer it added"))
        }

    override suspend fun setImage(project: ProjectId, objectIndex: Int, slot: Int, imagePath: String, name: String) =
        edit(project, objectIndex, TextureJson.request("image", slot) { put("path", imagePath); put("name", name) })

    override suspend fun setLayer(project: ProjectId, objectIndex: Int, slot: Int, settings: TextureLayerSettings) =
        edit(project, objectIndex, TextureJson.request("set", slot) { put("settings", TextureJson.settings(settings)) })

    override suspend fun removeLayer(project: ProjectId, objectIndex: Int, slot: Int) =
        edit(project, objectIndex, TextureJson.request("remove", slot))

    override suspend fun moveLayer(project: ProjectId, objectIndex: Int, slot: Int, by: Int) =
        edit(project, objectIndex, TextureJson.request("move", slot) { put("by", by) })

    override suspend fun setOptions(
        project: ProjectId,
        objectIndex: Int,
        smooth: Boolean,
        smoothStrength: Float,
        smoothPasses: Int,
        displaceBorder: Boolean,
    ) = edit(
        project,
        objectIndex,
        TextureJson.request("options") {
            put("smooth", smooth)
            put("smoothStrength", smoothStrength)
            put("smoothPasses", smoothPasses)
            put("displaceBorder", displaceBorder)
        },
    )

    override suspend fun paintAll(project: ProjectId, objectIndex: Int, slot: Int, erase: Boolean) =
        edit(project, objectIndex, TextureJson.request("paintAll", slot) { put("erase", erase) })

    override suspend fun bake(project: ProjectId, objectIndex: Int, onProgress: (Int) -> Unit): Result<TextureBake> = runCatching {
        val handle = registry.handleOf(project) ?: throw EngineException("The model is no longer loaded")
        coroutineScope {
            val token = NativeEngine.nativeCreateCancelToken()
            val work = async(dispatchers.io) {
                environment.ensureInitialized()
                NativeEngine.nativeTextureBake(handle, objectIndex, NativeProgressListener { percent, _ -> onProgress(percent) }, token)
            }
            // The token outlives a cancelled wait: the native call still holds it until it returns.
            work.invokeOnCompletion { NativeEngine.nativeReleaseCancelToken(token) }
            val payload = try {
                work.await()
            } catch (e: CancellationException) {
                NativeEngine.nativeRequestCancel(token)
                throw e
            }
            TextureJson.bake(payload).getOrThrow() ?: throw CancellationException("The bake was stopped")
        }
    }.onFailure { if (it is CancellationException) throw it }

    override suspend fun library(): Result<List<LibraryTexture>> = withContext(dispatchers.io) {
        runCatching {
            environment.ensureInitialized()
            TextureJson.library(NativeEngine.nativeTextureLibrary()).getOrThrow()
        }
    }

    private suspend fun edit(project: ProjectId, objectIndex: Int, request: String): Result<TextureStack> = call(project) { handle ->
        TextureJson.stack(NativeEngine.nativeTextureStack(handle, objectIndex, request)).getOrThrow().first
    }

    private suspend fun <T> call(project: ProjectId, work: (Long) -> T): Result<T> = withContext(dispatchers.io) {
        runCatching {
            val handle = registry.handleOf(project) ?: throw EngineException("The model is no longer loaded")
            work(handle)
        }
    }
}
