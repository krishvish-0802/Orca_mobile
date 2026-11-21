package com.orcamobile.core.engine

import com.orcamobile.core.model.ProjectId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps the [ProjectId]s the rest of the app uses to native project handles, so
 * native addresses never leave the engine and viewport modules.
 */
@Singleton
class NativeProjectRegistry @Inject constructor() {
    private val handles = ConcurrentHashMap<String, Long>()

    internal fun register(handle: Long): ProjectId =
        ProjectId(UUID.randomUUID().toString()).also { handles[it.value] = handle }

    /** The native handle for [id], or null once the project is released. Used by :core:viewport. */
    fun handleOf(id: ProjectId): Long? = handles[id.value]

    internal fun remove(id: ProjectId): Long? = handles.remove(id.value)
}
