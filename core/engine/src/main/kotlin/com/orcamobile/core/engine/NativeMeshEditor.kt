package com.orcamobile.core.engine

import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.project.MeshEditor
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.model.AssemblyStep
import com.orcamobile.core.model.CutRequest
import com.orcamobile.core.model.FeaturePick
import com.orcamobile.core.model.MeasureRequest
import com.orcamobile.core.model.ProjectId
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** [MeshEditor] over the bridge's mesh tools (native/bridge/src/CutTool.cpp). */
@Singleton
class NativeMeshEditor @Inject constructor(
    private val registry: NativeProjectRegistry,
    private val dispatchers: CoroutineDispatchers,
) : MeshEditor {

    override suspend fun checkCut(project: ProjectId, request: CutRequest) = call(project) { handle ->
        MeshToolsJson.cutCheck(NativeEngine.nativeCutPreview(handle, MeshToolsJson.encode(request))).getOrThrow()
    }

    override suspend fun cut(project: ProjectId, request: CutRequest) = call(project) { handle ->
        EngineJson.plateUpdate(NativeEngine.nativeCutPerform(handle, MeshToolsJson.encode(request))).getOrThrow()
    }

    override suspend fun measure(project: ProjectId, request: MeasureRequest) = call(project) { handle ->
        MeshToolsJson.measureOutcome(NativeEngine.nativeMeasure(handle, MeshToolsJson.encode(request))).getOrThrow()
    }

    override suspend fun assemble(
        project: ProjectId,
        first: FeaturePick,
        second: FeaturePick,
        request: MeasureRequest,
        step: AssemblyStep,
        value: Double,
        xyz: List<Double>,
    ) = call(project) { handle ->
        EngineJson.plateUpdate(
            NativeEngine.nativeAssemble(handle, MeshToolsJson.encodeAssemble(first, second, request.filter, step, value, xyz)),
        ).getOrThrow()
    }

    private suspend fun <T> call(project: ProjectId, work: (Long) -> T): Result<T> = withContext(dispatchers.io) {
        runCatching {
            val handle = registry.handleOf(project) ?: throw EngineException("The model is no longer loaded")
            work(handle)
        }
    }
}
