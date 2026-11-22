package com.orcamobile.core.engine

import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.project.ObjectEditor
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.model.ObjectEdit
import com.orcamobile.core.model.ObjectUpdate
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.SettingScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** [ObjectEditor] over the bridge (native/bridge/src/ProjectObjects.cpp). */
@Singleton
class NativeObjectEditor @Inject constructor(
    private val registry: NativeProjectRegistry,
    private val dispatchers: CoroutineDispatchers,
) : ObjectEditor {
    private val keys = ConcurrentHashMap<SettingScope, List<String>>()

    override suspend fun details(project: ProjectId, objectIndex: Int) = call(project) { handle ->
        NativeEngine.nativeObjectDetails(handle, objectIndex)
    }

    override suspend fun edit(project: ProjectId, objectIndex: Int, edit: ObjectEdit) = call(project) { handle ->
        NativeEngine.nativeObjectEdit(handle, objectIndex, EngineJson.encodeObjectEdit(edit))
    }

    override suspend fun settingKeys(scope: SettingScope): Result<List<String>> = withContext(dispatchers.io) {
        keys[scope]?.let { return@withContext Result.success(it) }
        EngineJson.stringList(NativeEngine.nativeSettingKeys(scope.nativeValue)).onSuccess { keys[scope] = it }
    }

    private suspend fun call(project: ProjectId, native: (Long) -> String): Result<ObjectUpdate> =
        withContext(dispatchers.io) {
            runCatching {
                val handle = registry.handleOf(project) ?: throw EngineException("The model is no longer loaded")
                EngineJson.objectUpdate(native(handle)).getOrThrow()
            }
        }
}
