package com.orcamobile.core.domain.model

/** App-private locations for generated files. */
interface WorkspaceRepository {
    /** A fresh path for G-code named after [baseName]. */
    fun newGcodePath(baseName: String): String
}
