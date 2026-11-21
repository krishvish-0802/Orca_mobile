package com.orcamobile.core.model

/** Identifies the OrcaSlicer engine build bundled with the app. */
data class EngineInfo(
    val appName: String,
    val orcaVersion: String,
    val slic3rVersion: String,
    val upstreamCommit: String,
)

/** Whether the native engine could be loaded on this device/build. */
sealed interface EngineStatus {
    data class Available(val info: EngineInfo) : EngineStatus

    /** The app was built without the native engine, or the library failed to load. */
    data class Unavailable(val reason: String) : EngineStatus
}
