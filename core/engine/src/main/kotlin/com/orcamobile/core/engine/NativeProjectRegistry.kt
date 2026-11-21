package com.orcamobile.core.engine

import com.orcamobile.core.model.ProjectId
import javax.inject.Inject
import javax.inject.Singleton

/** Maps the [ProjectId]s the rest of the app uses to native project handles. */
@Singleton
class NativeProjectRegistry @Inject constructor() {
    private val table = HandleTable()

    internal fun register(handle: Long): ProjectId = ProjectId(table.register(handle))

    /** The native handle for [id], or null once the project is released. Used by :core:viewport. */
    fun handleOf(id: ProjectId): Long? = table[id.value]

    internal fun remove(id: ProjectId): Long? = table.remove(id.value)
}
