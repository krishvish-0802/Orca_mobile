package com.orcamobile.core.domain.model

/** Brings a model the user picked (a content URI from the system file picker) into app storage. */
interface ModelImportRepository {
    /** Copies the document at [uri] and returns the local file path. */
    suspend fun import(uri: String): Result<String>
}
