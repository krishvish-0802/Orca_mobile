package com.orcamobile.core.domain.model

/** Bundled sample models (calibration cube, ...), materialized as files the engine can read. */
interface SampleModelRepository {
    /** Absolute path of the 20 mm calibration cube. */
    suspend fun calibrationCubePath(): String
}
