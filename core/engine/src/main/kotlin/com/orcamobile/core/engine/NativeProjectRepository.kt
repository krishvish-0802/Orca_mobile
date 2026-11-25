package com.orcamobile.core.engine

import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.project.ProjectRepository
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.model.HistoryAction
import com.orcamobile.core.model.HistoryState
import com.orcamobile.core.model.PlateConfig
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.ProjectPresets
import com.orcamobile.core.model.ProjectSummary
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** [ProjectRepository] over the bridge's Project (native/bridge/src/Project.cpp). */
@Singleton
class NativeProjectRepository @Inject constructor(
    private val environment: EngineEnvironment,
    private val registry: NativeProjectRegistry,
    private val dispatchers: CoroutineDispatchers,
) : ProjectRepository {

    override suspend fun load(path: String): Result<ProjectSummary> = withContext(dispatchers.io) {
        runCatching {
            if (!BuildConfig.NATIVE_ENGINE_BUNDLED) throw EngineException("This build does not include the native engine.")
            NativeEngine.loadError?.let { throw EngineException(it) }
            environment.ensureInitialized()
            val loaded = EngineJson.loadedProject(NativeEngine.nativeProjectLoad(path)).getOrThrow()
            ProjectSummary(registry.register(loaded.handle), path, loaded.objects)
        }
    }

    override suspend fun centerOn(id: ProjectId, x: Double, y: Double) = withContext(dispatchers.io) {
        registry.handleOf(id)?.let { NativeEngine.nativeProjectCenterOn(it, x, y) }
        Unit
    }

    override suspend fun filamentsChanged(id: ProjectId, removed: Int?, count: Int) = withContext(dispatchers.io) {
        registry.handleOf(id)?.let { NativeEngine.nativeProjectFilaments(it, removed ?: -1, count) }
        Unit
    }

    override suspend fun history(id: ProjectId, action: HistoryAction): Result<HistoryState> = withContext(dispatchers.io) {
        runCatching {
            val handle = registry.handleOf(id) ?: throw EngineException("The model is no longer loaded")
            EngineJson.history(NativeEngine.nativeProjectHistory(handle, action.nativeValue)).getOrThrow()
        }
    }

    override suspend fun save(id: ProjectId, path: String, config: PlateConfig): Result<Unit> = withContext(dispatchers.io) {
        runCatching {
            val handle = registry.handleOf(id) ?: throw EngineException("The model is no longer loaded")
            environment.ensureInitialized()
            val keys = config.overrides.keys.toTypedArray()
            val values = keys.map { config.overrides.getValue(it) }.toTypedArray()
            val result = EngineJson.parse(NativeEngine.nativeProjectSave(handle, path, EngineJson.encode(config.presets), keys, values))
            result["error"]?.jsonPrimitive?.content?.let { throw EngineException(it) }
            Unit
        }
    }

    override suspend fun savedPresets(id: ProjectId, vendor: String): Result<ProjectPresets?> = withContext(dispatchers.io) {
        runCatching {
            val handle = registry.handleOf(id) ?: throw EngineException("The model is no longer loaded")
            EngineJson.projectPresets(NativeEngine.nativeProjectPresets(handle, vendor))
        }
    }

    override fun release(id: ProjectId) {
        registry.remove(id)?.let(NativeEngine::nativeProjectRelease)
    }
}
