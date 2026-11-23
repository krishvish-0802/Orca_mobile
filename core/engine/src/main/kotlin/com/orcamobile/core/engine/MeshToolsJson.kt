package com.orcamobile.core.engine

import com.orcamobile.core.model.AssemblyOptions
import com.orcamobile.core.model.AssemblyStep
import com.orcamobile.core.model.ConnectorProblem
import com.orcamobile.core.model.CutCheck
import com.orcamobile.core.model.CutPlanePoint
import com.orcamobile.core.model.CutRequest
import com.orcamobile.core.model.FeatureFilter
import com.orcamobile.core.model.FeatureKind
import com.orcamobile.core.model.FeaturePick
import com.orcamobile.core.model.MeasureOutcome
import com.orcamobile.core.model.MeasureRequest
import com.orcamobile.core.model.MeasuredFeature
import com.orcamobile.core.model.Measurement
import com.orcamobile.core.model.SimplifyCounts
import com.orcamobile.core.model.SimplifySettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The mesh tools' JSON at the bridge (native/bridge/src/MeshToolsJson.cpp).
 * Public: the plate view (core:viewport) shows a cut with the same request.
 */
object MeshToolsJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(request: CutRequest): String = buildJsonObject {
        put("object", request.instance.objectIndex)
        put("instance", request.instance.instanceIndex)
        putJsonObject("plane") {
            put("height", request.plane.heightMm)
            put("tiltX", request.plane.tiltXDegrees)
            put("tiltY", request.plane.tiltYDegrees)
        }
        put("mode", request.mode.nativeValue)
        putJsonObject("options") {
            val o = request.options
            put("keepUpper", o.keepUpper)
            put("keepLower", o.keepLower)
            put("asParts", o.asParts)
            put("placeOnCutUpper", o.placeOnCutUpper)
            put("placeOnCutLower", o.placeOnCutLower)
            put("flipUpper", o.flipUpper)
            put("flipLower", o.flipLower)
        }
        putJsonArray("connectors") {
            for (c in request.connectors) addJsonObject {
                put("u", c.u)
                put("v", c.v)
                put("type", c.type.nativeValue)
                put("style", c.style.nativeValue)
                put("shape", c.shape.nativeValue)
                put("size", c.sizeMm)
                put("depth", c.depthMm)
                put("sizeTolerance", c.sizeToleranceMm)
                put("depthTolerance", c.depthToleranceMm)
                put("rotation", c.rotationDegrees)
            }
        }
        putJsonObject("dovetail") {
            val d = request.dovetail
            put("depth", d.depthMm)
            put("width", d.widthMm)
            put("flapsAngle", d.flapsAngleDegrees)
            put("angle", d.angleDegrees)
            put("depthTolerance", d.depthToleranceMm)
            put("widthTolerance", d.widthToleranceMm)
            put("count", d.count)
            put("gap", d.gapMm)
        }
    }.toString()

    fun cutCheck(payload: String): Result<CutCheck> = runCatching {
        val element = json.parseToJsonElement(payload)
        if (element is JsonObject && "error" in element) {
            throw EngineException(element["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown engine error")
        }
        val dto = json.decodeFromJsonElement(CutCheckDto.serializer(), element)
        CutCheck(
            boxMin = dto.boxMin,
            boxMax = dto.boxMax,
            center = dto.center,
            normal = dto.normal,
            problems = dto.problems.map(ConnectorProblem::of),
            canCut = dto.canCut,
            reason = dto.reason,
            dovetailDepthMm = dto.dovetailDepth,
        )
    }

    /** `{"u","v","inside"}`, or null for `{}` (the tap missed the plane). */
    fun planePoint(payload: String): CutPlanePoint? = runCatching {
        json.decodeFromString(PlanePointDto.serializer(), payload).takeIf { it.u != null && it.v != null }
            ?.let { CutPlanePoint(it.u!!, it.v!!, it.inside) }
    }.getOrNull()

    /** Simplify's arguments: `{"volume": n or null, "byRatio", "ratio", "detail"}`. */
    fun simplifyArguments(volume: Int?, settings: SimplifySettings): Map<String, JsonElement> = mapOf(
        "volume" to (volume?.let { JsonPrimitive(it) } ?: JsonNull),
        "byRatio" to JsonPrimitive(settings.byRatio),
        "ratio" to JsonPrimitive(settings.ratioPercent),
        "detail" to JsonPrimitive(settings.detail.nativeValue),
    )

    fun encode(volume: Int?, settings: SimplifySettings): String = JsonObject(simplifyArguments(volume, settings)).toString()

    fun simplifyCounts(payload: String): Result<SimplifyCounts> = runCatching {
        val element = json.parseToJsonElement(payload)
        if (element is JsonObject && "error" in element) {
            throw EngineException(element["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown engine error")
        }
        val dto = json.decodeFromJsonElement(SimplifyCountsDto.serializer(), element)
        SimplifyCounts(dto.before, dto.after)
    }

    fun encode(request: MeasureRequest): String = buildJsonObject {
        put("filter", request.filter.nativeValue)
        putJsonArray("picks") { for (p in request.picks) add(pick(p)) }
    }.toString()

    fun encodeAssemble(
        first: FeaturePick,
        second: FeaturePick,
        filter: FeatureFilter,
        step: AssemblyStep,
        value: Double,
        xyz: List<Double>,
    ): String = buildJsonObject {
        put("first", pick(first))
        put("second", pick(second))
        put("filter", filter.nativeValue)
        put("step", step.nativeValue)
        put("value", value)
        putJsonArray("xyz") { xyz.take(3).forEach { add(JsonPrimitive(it)) } }
    }.toString()

    fun pick(p: FeaturePick): JsonObject = buildJsonObject {
        put("object", p.objectIndex)
        put("instance", p.instanceIndex)
        put("volume", p.volume)
        put("facet", p.facet)
        putJsonArray("hit") { p.hit.forEach { add(JsonPrimitive(it)) } }
    }

    /** A pick from the plate: `{"object","instance","volume","facet","hit"}`, or null for `{}`. */
    fun featurePick(payload: String): FeaturePick? = runCatching {
        val dto = json.decodeFromString(PickDto.serializer(), payload)
        if (dto.`object` == null || dto.volume == null || dto.facet == null || dto.hit.size != 3) {
            null
        } else {
            FeaturePick(dto.`object`, dto.instance, dto.volume, dto.facet, dto.hit)
        }
    }.getOrNull()

    fun measureOutcome(payload: String): Result<MeasureOutcome> = runCatching {
        val element = json.parseToJsonElement(payload)
        if (element is JsonObject && "error" in element) {
            throw EngineException(element["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown engine error")
        }
        val dto = json.decodeFromJsonElement(MeasureOutcomeDto.serializer(), element)
        MeasureOutcome(
            features = dto.features.map { MeasuredFeature(FeatureKind.of(it.kind), it.a, it.b, it.radius) },
            measurement = dto.measurement?.let { Measurement(it.angle, it.distance, it.distanceInfinite, it.xyz) },
            assembly = dto.assembly.let {
                AssemblyOptions(
                    it.differentParts, it.sameObject, it.parallel, it.centerCoincidence, it.flipFirst, it.flipSecond,
                    it.rotateAround, it.hasParallelDistance, it.parallelDistance, it.angle, it.setXyz,
                )
            },
        )
    }

    @Serializable
    private data class PickDto(
        val `object`: Int? = null,
        val instance: Int = 0,
        val volume: Int? = null,
        val facet: Int? = null,
        val hit: List<Double> = emptyList(),
    )

    @Serializable
    private data class FeatureDto(val kind: Int, val a: List<Double>, val b: List<Double>, val radius: Double = 0.0)

    @Serializable
    private data class MeasurementDto(
        val angle: Double? = null,
        val distance: Double? = null,
        val distanceInfinite: Double? = null,
        val xyz: List<Double>? = null,
    )

    @Serializable
    private data class AssemblyDto(
        val differentParts: Boolean = false,
        val sameObject: Boolean = false,
        val parallel: Boolean = false,
        val centerCoincidence: Boolean = false,
        val flipFirst: Boolean = false,
        val flipSecond: Boolean = false,
        val rotateAround: Boolean = false,
        val hasParallelDistance: Boolean = false,
        val parallelDistance: Double = 0.0,
        val angle: Double = 0.0,
        val setXyz: Boolean = false,
    )

    @Serializable
    private data class MeasureOutcomeDto(
        val features: List<FeatureDto> = emptyList(),
        val measurement: MeasurementDto? = null,
        val assembly: AssemblyDto = AssemblyDto(),
    )

    @Serializable
    private data class SimplifyCountsDto(val before: Long = 0, val after: Long = 0)

    @Serializable
    private data class CutCheckDto(
        val boxMin: List<Double> = emptyList(),
        val boxMax: List<Double> = emptyList(),
        val center: List<Double> = emptyList(),
        val normal: List<Double> = emptyList(),
        val problems: List<Int> = emptyList(),
        val canCut: Boolean = false,
        val reason: String = "",
        val dovetailDepth: Double = 0.0,
    )

    @Serializable
    private data class PlanePointDto(val u: Double? = null, val v: Double? = null, val inside: Boolean = false)
}
