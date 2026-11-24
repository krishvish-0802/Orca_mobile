package com.orcamobile.core.engine

import com.orcamobile.core.model.LibraryTexture
import com.orcamobile.core.model.TextureBake
import com.orcamobile.core.model.TextureBlend
import com.orcamobile.core.model.TextureLayer
import com.orcamobile.core.model.TextureLayerSettings
import com.orcamobile.core.model.TextureProjection
import com.orcamobile.core.model.TextureStack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The bridge's texture displacement JSON (native/bridge/jni/TextureJni.cpp). */
internal object TextureJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun request(action: String, slot: Int? = null, extra: JsonObjectBuilder.() -> Unit = {}): String = buildJsonObject {
        put("action", action)
        slot?.let { put("slot", it) }
        extra()
    }.toString()

    fun settings(s: TextureLayerSettings): JsonObject = buildJsonObject {
        put("depth", s.depthMm)
        put("tile", s.tileMm)
        put("rotation", s.rotationDegrees)
        put("offsetX", s.offsetXMm)
        put("offsetY", s.offsetYMm)
        put("invert", s.invert)
        put("midlevel", s.midlevel)
        put("smoothing", s.smoothing)
        put("fadeEdge", s.fadeEdge)
        put("edgeFade", s.edgeFade)
        put("tiled", s.tiled)
        put("mirrorTiles", s.mirrorTiles)
        put("projection", s.projection.nativeValue)
        put("blend", s.blend.nativeValue)
    }

    /** The stack, and the slot an "add" made (or null). */
    fun stack(payload: String): Result<Pair<TextureStack, Int?>> = decode(payload) { o ->
        val stack = TextureStack(
            layers = o["layers"]?.jsonArray?.map { layer(it.jsonObject) }.orEmpty(),
            smooth = o.bool("smooth") ?: false,
            smoothStrength = o.float("smoothStrength") ?: 0.3f,
            smoothPasses = o.int("smoothPasses") ?: 2,
            displaceBorder = o.bool("displaceBorder") ?: true,
            hasModelUv = o.bool("hasModelUv") ?: false,
            full = o.bool("full") ?: false,
        )
        stack to o.int("slot")
    }

    fun bake(payload: String): Result<TextureBake?> = decode(payload) { o ->
        if (o.bool("canceled") == true) {
            null
        } else {
            TextureBake(
                parts = o.int("parts") ?: 0,
                trianglesBefore = o["trianglesBefore"]?.jsonPrimitive?.longOrNull ?: 0,
                trianglesAfter = o["trianglesAfter"]?.jsonPrimitive?.longOrNull ?: 0,
                budgetLimited = o.bool("budgetLimited") ?: false,
            )
        }
    }

    fun library(payload: String): Result<List<LibraryTexture>> = decode(payload) { o ->
        o["textures"]?.jsonArray?.map {
            val t = it.jsonObject
            LibraryTexture(t.string("name").orEmpty(), t.string("path").orEmpty())
        }.orEmpty()
    }

    private fun layer(o: JsonObject): TextureLayer {
        val s = o["settings"]?.jsonObject ?: JsonObject(emptyMap())
        val defaults = TextureLayerSettings()
        return TextureLayer(
            slot = o.int("slot") ?: -1,
            name = o.string("name").orEmpty(),
            path = o.string("path").orEmpty(),
            widthPx = o.int("width") ?: 0,
            heightPx = o.int("height") ?: 0,
            hasColour = o.bool("hasColour") ?: false,
            painted = o.bool("painted") ?: false,
            settings = TextureLayerSettings(
                depthMm = s.float("depth") ?: defaults.depthMm,
                tileMm = s.float("tile") ?: defaults.tileMm,
                rotationDegrees = s.float("rotation") ?: 0f,
                offsetXMm = s.float("offsetX") ?: 0f,
                offsetYMm = s.float("offsetY") ?: 0f,
                invert = s.bool("invert") ?: false,
                midlevel = s.float("midlevel") ?: 0f,
                smoothing = s.float("smoothing") ?: 0f,
                fadeEdge = s.bool("fadeEdge") ?: false,
                edgeFade = s.float("edgeFade") ?: defaults.edgeFade,
                tiled = s.bool("tiled") ?: true,
                mirrorTiles = s.bool("mirrorTiles") ?: false,
                projection = TextureProjection.of(s.int("projection") ?: 0),
                blend = TextureBlend.of(s.int("blend") ?: 0),
            ),
        )
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.float(key: String) = this[key]?.jsonPrimitive?.floatOrNull
    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull

    private fun <T> decode(payload: String, read: (JsonObject) -> T): Result<T> = runCatching {
        val element: JsonElement = json.parseToJsonElement(payload)
        val o = element.jsonObject
        if ("error" in o) throw EngineException(o.string("error") ?: "Unknown engine error")
        read(o)
    }
}
