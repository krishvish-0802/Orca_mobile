package com.orcamobile.core.engine

import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.project.ObjectTools
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.model.BrimEar
import com.orcamobile.core.model.EmbossSource
import com.orcamobile.core.model.FeaturePick
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.LayerProfileAction
import com.orcamobile.core.model.PlateConfig
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.VolumeKind
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** [ObjectTools] over the bridge (native/bridge/src/LayerHeights.cpp, BrimEars.cpp, EmbossTool.cpp). */
@Singleton
class NativeObjectTools @Inject constructor(
    private val environment: EngineEnvironment,
    private val registry: NativeProjectRegistry,
    private val dispatchers: CoroutineDispatchers,
) : ObjectTools {

    override suspend fun layerProfile(project: ProjectId, objectIndex: Int, config: PlateConfig, action: LayerProfileAction) =
        call(project) { handle ->
            environment.ensureInitialized()
            val keys = config.overrides.keys.toTypedArray()
            val values = keys.map { config.overrides.getValue(it) }.toTypedArray()
            ObjectToolsJson.layerProfile(
                NativeEngine.nativeLayerProfile(handle, objectIndex, EngineJson.encode(config.presets), keys, values, ObjectToolsJson.encode(action)),
            ).getOrThrow()
        }

    override suspend fun brimEars(project: ProjectId, instance: InstanceRef) = ears(project, instance, ObjectToolsJson.encodeEarsRequest("get"))

    override suspend fun setBrimEars(project: ProjectId, instance: InstanceRef, ears: List<BrimEar>) =
        ears(project, instance, ObjectToolsJson.encodeEarsRequest("set", ears))

    override suspend fun findBrimEars(project: ProjectId, instance: InstanceRef, maxAngleDegrees: Double, detectionMm: Double, radiusMm: Double) =
        ears(project, instance, ObjectToolsJson.encodeEarsRequest("find", maxAngle = maxAngleDegrees, detection = detectionMm, radius = radiusMm))

    private suspend fun ears(project: ProjectId, instance: InstanceRef, request: String) = call(project) { handle ->
        ObjectToolsJson.ears(NativeEngine.nativeBrimEars(handle, instance.objectIndex, instance.instanceIndex, request)).getOrThrow()
    }

    override suspend fun embossSize(source: EmbossSource) = withContext(dispatchers.io) {
        runCatching { ObjectToolsJson.embossSize(NativeEngine.nativeEmbossSize(ObjectToolsJson.source(source).toString())).getOrThrow() }
    }

    override suspend fun embossOnSurface(
        project: ProjectId,
        at: FeaturePick,
        kind: VolumeKind,
        source: EmbossSource,
        useSurface: Boolean,
    ) = call(project) { handle ->
        val request = buildJsonObject {
            put("source", ObjectToolsJson.source(source))
            put("pick", MeshToolsJson.pick(at))
            put("kind", kind.nativeValue)
            put("surface", useSurface)
        }
        EngineJson.plateUpdate(NativeEngine.nativeEmboss(handle, request.toString())).getOrThrow()
    }

    override suspend fun embossed(project: ProjectId, objectIndex: Int, volume: Int) = call(project) { handle ->
        ObjectToolsJson.embossed(NativeEngine.nativeEmbossed(handle, objectIndex, volume)).getOrThrow()
    }

    override suspend fun embossUpdate(
        project: ProjectId,
        objectIndex: Int,
        volume: Int,
        kind: VolumeKind,
        source: EmbossSource,
        useSurface: Boolean,
    ) = call(project) { handle ->
        val request = buildJsonObject {
            put("source", ObjectToolsJson.source(source))
            put("kind", kind.nativeValue)
            put("surface", useSurface)
        }
        EngineJson.plateUpdate(NativeEngine.nativeEmbossUpdate(handle, objectIndex, volume, request.toString())).getOrThrow()
    }

    override suspend fun embossMove(project: ProjectId, volume: Int, at: FeaturePick) = call(project) { handle ->
        val request = buildJsonObject { put("pick", MeshToolsJson.pick(at)) }
        EngineJson.plateUpdate(NativeEngine.nativeEmbossMove(handle, volume, request.toString())).getOrThrow()
    }

    override suspend fun embossSettle(project: ProjectId, objectIndex: Int) = call(project) { handle ->
        EngineJson.plateUpdate(NativeEngine.nativeEmbossSettle(handle, objectIndex)).getOrThrow()
    }

    override suspend fun embossObject(project: ProjectId, source: EmbossSource, x: Double, y: Double) = call(project) { handle ->
        val request = buildJsonObject {
            put("source", ObjectToolsJson.source(source))
            put("x", x)
            put("y", y)
        }
        EngineJson.plateUpdate(NativeEngine.nativeEmboss(handle, request.toString())).getOrThrow()
    }

    private suspend fun <T> call(project: ProjectId, work: (Long) -> T): Result<T> = withContext(dispatchers.io) {
        runCatching {
            val handle = registry.handleOf(project) ?: throw EngineException("The model is no longer loaded")
            work(handle)
        }
    }
}
