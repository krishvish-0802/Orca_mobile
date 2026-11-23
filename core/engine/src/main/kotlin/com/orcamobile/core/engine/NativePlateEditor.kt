package com.orcamobile.core.engine

import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.project.PlateEditor
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.model.ArrangeOptions
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.PlateAction
import com.orcamobile.core.model.PlateConfig
import com.orcamobile.core.model.PlateUpdate
import com.orcamobile.core.model.Plates
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.TransformEdit
import com.orcamobile.core.model.TransformUpdate
import com.orcamobile.core.model.WipeTower
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** [PlateEditor] over the bridge's plate tools (native/bridge/src/PlateTools.cpp). */
@Singleton
class NativePlateEditor @Inject constructor(
    private val environment: EngineEnvironment,
    private val registry: NativeProjectRegistry,
    private val dispatchers: CoroutineDispatchers,
) : PlateEditor {

    override suspend fun arrange(project: ProjectId, config: PlateConfig, options: ArrangeOptions, allPlates: Boolean) =
        call(project, config) { handle, presets, keys, values ->
            NativeEngine.nativePlateArrange(handle, presets, keys, values, EngineJson.encode(options, allPlates))
        }

    override suspend fun plates(project: ProjectId, action: PlateAction): Result<Plates> = withContext(dispatchers.io) {
        runCatching {
            val handle = registry.handleOf(project) ?: throw EngineException("The model is no longer loaded")
            EngineJson.plates(NativeEngine.nativePlates(handle, EngineJson.encode(action))).getOrThrow()
        }
    }

    override suspend fun orient(project: ProjectId, instance: InstanceRef?, config: PlateConfig) =
        call(project, config) { handle, presets, keys, values ->
            NativeEngine.nativePlateOrient(
                handle, instance?.objectIndex ?: -1, instance?.instanceIndex ?: -1, presets, keys, values,
            )
        }

    override suspend fun addCopies(
        project: ProjectId,
        instance: InstanceRef,
        count: Int?,
        config: PlateConfig,
        options: ArrangeOptions,
    ) = call(project, config) { handle, presets, keys, values ->
        NativeEngine.nativePlateAddCopies(
            handle, instance.objectIndex, instance.instanceIndex, count ?: -1, presets, keys, values,
            EngineJson.encode(options),
        )
    }

    override suspend fun transform(
        project: ProjectId,
        instance: InstanceRef,
        edit: TransformEdit?,
    ): Result<TransformUpdate> = withContext(dispatchers.io) {
        runCatching {
            val handle = registry.handleOf(project) ?: throw EngineException("The model is no longer loaded")
            EngineJson.transformUpdate(
                NativeEngine.nativeInstanceTransform(handle, instance.objectIndex, instance.instanceIndex, EngineJson.encodeEdit(edit)),
            ).getOrThrow()
        }
    }

    override suspend fun splitToObjects(project: ProjectId, objectIndex: Int) = withContext(dispatchers.io) {
        runCatching {
            val handle = registry.handleOf(project) ?: throw EngineException("The model is no longer loaded")
            EngineJson.plateUpdate(NativeEngine.nativePlateSplit(handle, objectIndex)).getOrThrow()
        }
    }

    override suspend fun remove(project: ProjectId, instance: InstanceRef) = withContext(dispatchers.io) {
        runCatching {
            val handle = registry.handleOf(project) ?: throw EngineException("The model is no longer loaded")
            EngineJson.plateUpdate(NativeEngine.nativeProjectRemove(handle, instance.objectIndex, instance.instanceIndex))
                .getOrThrow()
        }
    }

    override suspend fun wipeTower(project: ProjectId, config: PlateConfig): Result<WipeTower?> = withContext(dispatchers.io) {
        runCatching {
            val handle = registry.handleOf(project) ?: throw EngineException("The model is no longer loaded")
            environment.ensureInitialized()
            val keys = config.overrides.keys.toTypedArray()
            val values = keys.map { config.overrides.getValue(it) }.toTypedArray()
            EngineJson.wipeTower(NativeEngine.nativePlateWipeTower(handle, EngineJson.encode(config.presets), keys, values)).getOrThrow()
        }
    }

    private suspend fun call(
        project: ProjectId,
        config: PlateConfig,
        native: (handle: Long, presets: String, keys: Array<String>, values: Array<String>) -> String,
    ): Result<PlateUpdate> = withContext(dispatchers.io) {
        runCatching {
            val handle = registry.handleOf(project) ?: throw EngineException("The model is no longer loaded")
            environment.ensureInitialized()
            val keys = config.overrides.keys.toTypedArray()
            val values = keys.map { config.overrides.getValue(it) }.toTypedArray()
            EngineJson.plateUpdate(native(handle, EngineJson.encode(config.presets), keys, values)).getOrThrow()
        }
    }
}
