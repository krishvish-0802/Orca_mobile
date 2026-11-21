package com.orcamobile.core.engine

import com.orcamobile.core.model.PreviewId
import javax.inject.Inject
import javax.inject.Singleton

/** Maps [PreviewId]s to native G-code preview handles. */
@Singleton
class NativePreviewRegistry @Inject constructor() {
    private val table = HandleTable()

    internal fun register(handle: Long): PreviewId = PreviewId(table.register(handle))

    /** The native handle for [id], or null once the preview is released. Used by :core:viewport. */
    fun handleOf(id: PreviewId): Long? = table[id.value]

    internal fun remove(id: PreviewId): Long? = table.remove(id.value)
}
