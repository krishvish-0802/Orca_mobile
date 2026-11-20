package com.orcamobile.core.engine

import com.orcamobile.core.model.EngineInfo
import com.orcamobile.core.model.SliceResult
import com.orcamobile.core.model.SliceStatistics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Decodes the JSON the native bridge returns (native/bridge/src/JsonCodec.cpp)
 * into domain models. The DTOs mirror that file field for field.
 */
internal object EngineJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun engineInfo(payload: String): EngineInfo =
        json.decodeFromString<EngineInfoDto>(payload).let {
            EngineInfo(it.appName, it.orcaVersion, it.slic3rVersion, it.upstreamCommit)
        }

    fun sliceResult(payload: String, gcodePath: String): SliceResult {
        val dto = json.decodeFromString<SliceOutcomeDto>(payload)
        return when (dto.status) {
            "succeeded" -> dto.statistics?.let { SliceResult.Succeeded(gcodePath, it.toModel()) }
                ?: SliceResult.Failed("Engine reported success without statistics")
            "canceled" -> SliceResult.Canceled
            else -> SliceResult.Failed(dto.error ?: "Slicing failed")
        }
    }

    @Serializable
    private data class EngineInfoDto(
        val appName: String,
        val orcaVersion: String,
        val slic3rVersion: String,
        val upstreamCommit: String,
    )

    @Serializable
    private data class SliceOutcomeDto(
        val status: String,
        val error: String? = null,
        val statistics: StatisticsDto? = null,
    )

    @Serializable
    private data class StatisticsDto(
        val printTimeSeconds: Double,
        val filamentUsedMm: Double,
        val filamentUsedG: Double,
        val layerCount: Int,
        val gcodeBytes: Long,
    ) {
        fun toModel() = SliceStatistics(printTimeSeconds, filamentUsedMm, filamentUsedG, layerCount, gcodeBytes)
    }
}
