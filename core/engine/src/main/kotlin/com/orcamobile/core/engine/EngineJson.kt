package com.orcamobile.core.engine

import com.orcamobile.core.model.ArrangeOptions
import com.orcamobile.core.model.Axes
import com.orcamobile.core.model.Axis
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.CompatiblePresets
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.EngineInfo
import com.orcamobile.core.model.GCodePreviewSummary
import com.orcamobile.core.model.InstanceTransform
import com.orcamobile.core.model.LayerRange
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.ObjectDetails
import com.orcamobile.core.model.ObjectEdit
import com.orcamobile.core.model.ObjectUpdate
import com.orcamobile.core.model.OptionChoice
import com.orcamobile.core.model.OptionMode
import com.orcamobile.core.model.OptionType
import com.orcamobile.core.model.PlateUpdate
import com.orcamobile.core.model.PresetOptionKeys
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PreviewId
import com.orcamobile.core.model.SettingTarget
import com.orcamobile.core.model.SliceResult
import com.orcamobile.core.model.SliceStatistics
import com.orcamobile.core.model.TransformEdit
import com.orcamobile.core.model.TransformUpdate
import com.orcamobile.core.model.VolumeInfo
import com.orcamobile.core.model.VolumeKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

    fun plateUpdate(payload: String): Result<PlateUpdate> = decodeOrError(payload) { element ->
        val dto = json.decodeFromJsonElement(PlateUpdateDto.serializer(), element)
        PlateUpdate(dto.objects.map { it.toModel() }, dto.placed, dto.notPlaced)
    }

    fun transformUpdate(payload: String): Result<TransformUpdate> = decodeOrError(payload) { element ->
        val dto = json.decodeFromJsonElement(TransformUpdateDto.serializer(), element)
        TransformUpdate(dto.transform.toModel(), dto.objects.map { it.toModel() })
    }

    fun objectUpdate(payload: String): Result<ObjectUpdate> = decodeOrError(payload) { element ->
        val dto = json.decodeFromJsonElement(ObjectUpdateDto.serializer(), element)
        val o = dto.`object`
        ObjectUpdate(
            ObjectDetails(
                name = o.name,
                settings = o.settings,
                volumes = o.volumes.map {
                    VolumeInfo(it.name, VolumeKind.of(it.kind), it.triangles, it.settings, it.transform.toModel())
                },
                layerRanges = o.layerRanges.map { LayerRange(it.min, it.max, it.settings) },
            ),
            dto.objects.map { it.toModel() },
        )
    }

    /** The edit as native_object_edit reads it (native/bridge/jni/ObjectJni.cpp). */
    fun encodeObjectEdit(edit: ObjectEdit): String {
        fun target(target: SettingTarget): Map<String, JsonElement> = buildMap {
            target.volume?.let { put("volume", JsonPrimitive(it)) }
            target.range?.let { put("range", JsonPrimitive(it)) }
        }
        val (name, arguments) = when (edit) {
            is ObjectEdit.AddPrimitive -> "addPrimitive" to mapOf(
                "kind" to JsonPrimitive(edit.kind.nativeValue),
                "shape" to JsonPrimitive(edit.shape.nativeValue),
                "size" to JsonPrimitive(edit.sizeMm),
            )
            is ObjectEdit.AddFile -> "addFile" to mapOf("kind" to JsonPrimitive(edit.kind.nativeValue), "path" to JsonPrimitive(edit.path))
            is ObjectEdit.RemoveVolume -> "removeVolume" to mapOf("volume" to JsonPrimitive(edit.volume))
            is ObjectEdit.SetKind -> "setKind" to mapOf("volume" to JsonPrimitive(edit.volume), "kind" to JsonPrimitive(edit.kind.nativeValue))
            is ObjectEdit.Rename -> "rename" to buildMap {
                edit.volume?.let { put("volume", JsonPrimitive(it)) }
                put("name", JsonPrimitive(edit.name))
            }
            is ObjectEdit.VolumeTransform -> "volumeTransform" to mapOf(
                "volume" to JsonPrimitive(edit.volume),
                "position" to edit.position.json(),
                "rotation" to edit.rotationDegrees.json(),
                "scale" to edit.scale.json(),
            )
            is ObjectEdit.SetSetting -> "set" to target(edit.target) + mapOf("key" to JsonPrimitive(edit.key), "value" to JsonPrimitive(edit.value))
            is ObjectEdit.ResetSetting -> "reset" to target(edit.target) + mapOf("key" to JsonPrimitive(edit.key))
            is ObjectEdit.AddRange -> "addRange" to mapOf(
                "min" to JsonPrimitive(edit.minZ),
                "max" to JsonPrimitive(edit.maxZ),
                "layerHeight" to JsonPrimitive(edit.layerHeight),
            )
            is ObjectEdit.SetRange -> "setRange" to mapOf(
                "range" to JsonPrimitive(edit.range),
                "min" to JsonPrimitive(edit.minZ),
                "max" to JsonPrimitive(edit.maxZ),
            )
            is ObjectEdit.RemoveRange -> "removeRange" to mapOf("range" to JsonPrimitive(edit.range))
        }
        return JsonObject(mapOf(name to JsonObject(arguments))).toString()
    }

    /** The edit as native_instance_transform reads it; "" reads without a change. */
    fun encodeEdit(edit: TransformEdit?): String {
        val body: JsonElement = when (edit) {
            null -> return ""
            is TransformEdit.Position -> buildJsonObject { put("position", JsonArray(listOf(JsonPrimitive(edit.x), JsonPrimitive(edit.y)))) }
            is TransformEdit.Rotation -> buildJsonObject { put("rotation", edit.degrees.json()) }
            is TransformEdit.Scale -> buildJsonObject { put("scale", edit.factors.json()) }
            is TransformEdit.Mirror -> buildJsonObject { put("mirror", JsonPrimitive(edit.axis.ordinal)) }
            TransformEdit.Reset -> buildJsonObject { put("reset", JsonPrimitive(true)) }
        }
        return body.toString()
    }

    private fun Axes.json() = JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y), JsonPrimitive(z)))

    private fun List<Double>.axes() = Axes(getOrElse(0) { 0.0 }, getOrElse(1) { 0.0 }, getOrElse(2) { 0.0 })

    fun encode(options: ArrangeOptions): String =
        json.encodeToString(
            ArrangeOptionsDto.serializer(),
            ArrangeOptionsDto(options.spacingMm, options.allowRotation, options.alignToYAxis),
        )

    fun configOptions(payload: String): Result<List<ConfigOption>> = decodeOrError(payload) { element ->
        json.decodeFromJsonElement(OptionsDto.serializer(), element).options.map { it.toModel() }
    }

    fun presetOptionKeys(payload: String): Result<PresetOptionKeys> = decodeOrError(payload) { element ->
        val dto = json.decodeFromJsonElement(PresetOptionKeysDto.serializer(), element)
        PresetOptionKeys(dto.process, dto.filament, dto.printer)
    }

    fun configValues(payload: String): Result<Map<String, String>> = decodeOrError(payload) { element ->
        json.decodeFromJsonElement(ValuesDto.serializer(), element).values
    }

    data class LoadedPreview(val handle: Long, val summary: GCodePreviewSummary)

    /** The summary's id and path are placeholders; the repository fills them in. */
    fun loadedPreview(payload: String): Result<LoadedPreview> = decodeOrError(payload) { element ->
        val dto = json.decodeFromJsonElement(LoadedPreviewDto.serializer(), element)
        LoadedPreview(
            dto.handle.toLong(),
            GCodePreviewSummary(
                id = PreviewId(""),
                gcodePath = "",
                layerZs = dto.layerZs,
                printTimeSeconds = dto.printTimeSeconds,
                moveCount = dto.moveCount,
                filamentColors = dto.filamentColors,
            ),
        )
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
    private data class TransformDto(
        val position: List<Double>,
        val rotation: List<Double>,
        val scale: List<Double>,
        val size: List<Double>,
        val mirrored: List<Boolean>,
    ) {
        fun toModel() = InstanceTransform(
            position = position.axes(),
            rotationDegrees = rotation.axes(),
            scale = scale.axes(),
            size = size.axes(),
            mirrored = Axis.entries.filterIndexed { i, _ -> mirrored.getOrElse(i) { false } }.toSet(),
        )
    }

    @Serializable
    private data class VolumeDto(
        val name: String,
        val kind: Int,
        val triangles: Long = 0,
        val settings: Map<String, String> = emptyMap(),
        val transform: TransformDto,
    )

    @Serializable
    private data class LayerRangeDto(val min: Double, val max: Double, val settings: Map<String, String> = emptyMap())

    @Serializable
    private data class ObjectDetailsDto(
        val name: String,
        val settings: Map<String, String> = emptyMap(),
        val volumes: List<VolumeDto> = emptyList(),
        val layerRanges: List<LayerRangeDto> = emptyList(),
    )

    @Serializable
    private data class ObjectUpdateDto(val `object`: ObjectDetailsDto, val objects: List<ObjectDto>)

    @Serializable
    private data class TransformUpdateDto(val transform: TransformDto, val objects: List<ObjectDto>)

    @Serializable
    private data class PlateUpdateDto(val objects: List<ObjectDto>, val placed: Int = 0, val notPlaced: Int = 0)

    @Serializable
    private data class ArrangeOptionsDto(val spacingMm: Double, val allowRotation: Boolean, val alignToYAxis: Boolean)

    @Serializable
    private data class LoadedProjectDto(val handle: String, val objects: List<ObjectDto>)

    @Serializable
    private data class OptionDto(
        val key: String,
        val type: String,
        val label: String = "",
        val fullLabel: String = "",
        val category: String = "",
        val tooltip: String = "",
        val unit: String = "",
        val default: String = "",
        val min: Double? = null,
        val max: Double? = null,
        val enumValues: List<String> = emptyList(),
        val enumLabels: List<String> = emptyList(),
        val mode: String = "simple",
        val nullable: Boolean = false,
        val multiline: Boolean = false,
    ) {
        fun toModel() = ConfigOption(
            key = key,
            type = OptionType.fromWire(type),
            label = label.ifEmpty { key },
            fullLabel = fullLabel.ifEmpty { label.ifEmpty { key } },
            category = category,
            tooltip = tooltip,
            unit = unit,
            defaultValue = default,
            min = min,
            max = max,
            choices = enumValues.mapIndexed { i, value -> OptionChoice(value, enumLabels.getOrNull(i)?.ifEmpty { null } ?: value) },
            mode = OptionMode.fromWire(mode),
            nullable = nullable,
            multiline = multiline,
        )
    }

    @Serializable
    private data class PresetOptionKeysDto(
        val process: List<String> = emptyList(),
        val filament: List<String> = emptyList(),
        val printer: List<String> = emptyList(),
    )

    @Serializable
    private data class OptionsDto(val options: List<OptionDto>)

    @Serializable
    private data class ValuesDto(val values: Map<String, String>)

    @Serializable
    private data class LoadedPreviewDto(
        val handle: String,
        val layerZs: List<Float>,
        val printTimeSeconds: Double = 0.0,
        val moveCount: Long = 0,
        val filamentColors: List<String> = emptyList(),
    )

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
