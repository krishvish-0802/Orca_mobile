package com.orcamobile.core.engine

import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.CompatiblePresets
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.EngineInfo
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.SliceResult
import com.orcamobile.core.model.SliceStatistics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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

    /** A JSON array of strings, or a failure carrying the engine's `{"error": ...}` message. */
    fun stringList(payload: String): Result<List<String>> = runCatching {
        when (val element = json.parseToJsonElement(payload)) {
            is JsonArray -> element.map { it.jsonPrimitive.content }
            is JsonObject -> throw EngineException(element.errorMessage())
            else -> throw EngineException("Unexpected engine response")
        }
    }

    fun compatiblePresets(payload: String): Result<CompatiblePresets> = runCatching {
        val element = json.parseToJsonElement(payload)
        if (element is JsonObject && "error" in element) throw EngineException(element.errorMessage())
        val dto = json.decodeFromJsonElement(CompatiblePresetsDto.serializer(), element)
        CompatiblePresets(
            processes = dto.processes,
            filaments = dto.filaments,
            defaultProcess = dto.defaultProcess.ifEmpty { null },
            defaultFilament = dto.defaultFilament.ifEmpty { null },
        )
    }

    fun bedShape(payload: String): Result<BedShape> = decodeOrError(payload) { element ->
        val dto = json.decodeFromJsonElement(BedShapeDto.serializer(), element)
        BedShape(dto.outline.map { it[0] to it[1] }, dto.maxHeight)
    }

    /** A loaded project: its native handle and objects. */
    data class LoadedProject(val handle: Long, val objects: List<ModelObjectInfo>)

    fun loadedProject(payload: String): Result<LoadedProject> = decodeOrError(payload) { element ->
        val dto = json.decodeFromJsonElement(LoadedProjectDto.serializer(), element)
        LoadedProject(dto.handle.toLong(), dto.objects.map { it.toModel() })
    }

    fun projectObjects(payload: String): Result<List<ModelObjectInfo>> = decodeOrError(payload) { element ->
        json.decodeFromJsonElement(ObjectsDto.serializer(), element).objects.map { it.toModel() }
    }

    private fun <T> decodeOrError(payload: String, decode: (kotlinx.serialization.json.JsonElement) -> T): Result<T> =
        runCatching {
            val element = json.parseToJsonElement(payload)
            if (element is JsonObject && "error" in element) throw EngineException(element.errorMessage())
            decode(element)
        }

    fun encode(selection: PresetSelection?): String =
        selection?.let {
            json.encodeToString(
                PresetSelectionDto.serializer(),
                PresetSelectionDto(it.vendor, it.printer, it.process, it.filaments),
            )
        } ?: ""

    private fun JsonObject.errorMessage(): String =
        this["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown engine error"

    @Serializable
    private data class BedShapeDto(val outline: List<List<Double>>, val maxHeight: Double = 0.0)

    @Serializable
    private data class ObjectDto(
        val name: String,
        val instances: Int,
        val triangles: Long,
        val size: List<Double>,
    ) {
        fun toModel() = ModelObjectInfo(name, instances, triangles, size)
    }

    @Serializable
    private data class ObjectsDto(val objects: List<ObjectDto>)

    @Serializable
    private data class LoadedProjectDto(val handle: String, val objects: List<ObjectDto>)

    @Serializable
    private data class CompatiblePresetsDto(
        val processes: List<String>,
        val filaments: List<String>,
        val defaultProcess: String = "",
        val defaultFilament: String = "",
    )

    @Serializable
    private data class PresetSelectionDto(
        val vendor: String,
        val printer: String,
        val process: String,
        val filaments: List<String>,
    )

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
