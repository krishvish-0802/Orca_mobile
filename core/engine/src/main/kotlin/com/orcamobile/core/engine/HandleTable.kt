package com.orcamobile.core.engine

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Native handles behind opaque string ids, so addresses never leave the engine and viewport modules. */
internal class HandleTable {
    private val handles = ConcurrentHashMap<String, Long>()

    fun register(handle: Long): String = UUID.randomUUID().toString().also { handles[it] = handle }

    operator fun get(id: String): Long? = handles[id]

    fun remove(id: String): Long? = handles.remove(id)
}
