package com.orcamobile.core.engine

import com.orcamobile.core.model.BrimEar
import com.orcamobile.core.model.EmbossSource
import com.orcamobile.core.model.EmbossedVolume
import com.orcamobile.core.model.LayerProfile
import com.orcamobile.core.model.LayerProfileAction
import com.orcamobile.core.model.VolumeKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The object tools' JSON at the bridge (native/bridge/jni/ToolsJni.cpp).
 * Public: the plate view (core:viewport) shows brim ears with the same encoding.
 */
object ObjectToolsJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(action: LayerProfileAction): String = buildJsonObject {
        when (action) {
            LayerProfileAction.Get -> put("action", "get")
            is LayerProfileAction.Adaptive -> {
                put("action", "adaptive")
                put("quality", action.quality)
            }
            is LayerProfileAction.Smooth -> {
                put("action", "smooth")
                put("radius", action.radius)
                put("keepMin", action.keepMin)
            }
            is LayerProfileAction.Brush -> {
                put("action", "brush")
                put("z", action.zMm)
                put("delta", action.deltaMm)
                put("band", action.bandMm)
                put("brush", action.brush.nativeValue)
            }
            LayerProfileAction.Reset -> put("action", "reset")
        }
    }.toString()

    fun layerProfile(payload: String): Result<LayerProfile> = decode(payload) { element ->
        val dto = json.decodeFromJsonElement(LayerProfileDto.serializer(), element)
        LayerProfile(dto.profile, dto.layers, dto.layerHeight, dto.minLayerHeight, dto.maxLayerHeight, dto.objectHeight, dto.custom)
    }

    /** `[[x, y, r], ..]`. */
    fun encodeEars(ears: List<BrimEar>): String =
        JsonArray(ears.map { JsonArray(listOf(JsonPrimitive(it.x), JsonPrimitive(it.y), JsonPrimitive(it.radius))) }).toString()

    fun encodeEarsRequest(action: String, ears: List<BrimEar> = emptyList(), maxAngle: Double = 0.0, detection: Double = 0.0, radius: Double = 0.0): String =
        buildJsonObject {
            put("action", action)
            put("ears", json.parseToJsonElement(encodeEars(ears)))
            put("maxAngle", maxAngle)
            put("detection", detection)
            put("radius", radius)
        }.toString()

    fun ears(payload: String): Result<List<BrimEar>> = decode(payload) { element ->
        element.jsonObject["ears"]!!.jsonArray.map { ear ->
            val v = ear.jsonArray
            BrimEar(v[0].jsonPrimitive.double, v[1].jsonPrimitive.double, v[2].jsonPrimitive.double)
        }
    }

    /** A tap snapped to an outline: `{"x","y","bedX","bedY"}`; x and y only when it was near the outline. */
    data class EarPoint(val x: Double?, val y: Double?, val bedX: Double, val bedY: Double)

    fun earPoint(payload: String): EarPoint? = runCatching {
        val dto = json.decodeFromString(EarPointDto.serializer(), payload)
        if (dto.bedX == null || dto.bedY == null) null else EarPoint(dto.x, dto.y, dto.bedX, dto.bedY)
    }.getOrNull()

    fun source(source: EmbossSource): JsonObject = buildJsonObject {
        put("text", source.text)
        put("font", source.fontPath)
        put("height", source.letterHeightMm)
        put("charGap", source.charGap)
        put("lineGap", source.lineGap)
        put("boldness", source.boldnessMm)
        put("skew", source.skew)
        put("svg", source.svgPath)
        put("svgWidth", source.svgWidthMm)
        put("depth", source.depthMm)
    }

    /** An embossed volume's settings, or null for `{}` (not a text or SVG). */
    fun embossed(payload: String): Result<EmbossedVolume?> = decode(payload) { element ->
        val o = element.jsonObject
        val s = o["source"]?.jsonObject ?: return@decode null
        fun num(key: String, fallback: Double = 0.0) = s[key]?.jsonPrimitive?.doubleOrNull ?: fallback
        fun str(key: String) = s[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        EmbossedVolume(
            source = EmbossSource(
                text = str("text"),
                fontPath = str("font"),
                letterHeightMm = num("height", 10.0),
                charGap = num("charGap"),
                lineGap = num("lineGap"),
                boldnessMm = num("boldness"),
                skew = num("skew"),
                svgPath = str("svg"),
                svgWidthMm = num("svgWidth"),
                depthMm = num("depth", 2.0),
            ),
            kind = VolumeKind.of(o["kind"]?.jsonPrimitive?.intOrNull ?: 0),
            useSurface = o["surface"]?.jsonPrimitive?.booleanOrNull ?: false,
            widthMm = o["width"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            heightMm = o["height"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
    }

    fun embossSize(payload: String): Result<Pair<Double, Double>> = decode(payload) { element ->
        val o = element.jsonObject
        o["width"]!!.jsonPrimitive.double to o["height"]!!.jsonPrimitive.double
    }

    private fun <T> decode(payload: String, read: (kotlinx.serialization.json.JsonElement) -> T): Result<T> = runCatching {
        val element = json.parseToJsonElement(payload)
        if (element is JsonObject && "error" in element) {
            throw EngineException(element["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown engine error")
        }
        read(element)
    }

    @Serializable
    private data class LayerProfileDto(
        val profile: List<Double> = emptyList(),
        val layers: List<Double> = emptyList(),
        val layerHeight: Double = 0.0,
        val minLayerHeight: Double = 0.0,
        val maxLayerHeight: Double = 0.0,
        val objectHeight: Double = 0.0,
        val custom: Boolean = false,
    )

    @Serializable
    private data class EarPointDto(val x: Double? = null, val y: Double? = null, val bedX: Double? = null, val bedY: Double? = null)
}
