package com.orcamobile.core.printer.moonraker

import com.orcamobile.core.printer.http.apiKey
import com.orcamobile.core.printer.http.await
import com.orcamobile.core.printer.http.bodyOrThrow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.URLEncoder
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Moonraker's HTTP API at one printer: which of the candidate addresses
 * answers (see [moonrakerCandidates]), and JSON calls that return `result`.
 */
internal class MoonrakerApi(
    /** Base URLs to try, in order. */
    private val candidates: List<HttpUrl>,
    private val apiKey: String?,
    private val http: OkHttpClient,
) {
    init {
        require(candidates.isNotEmpty()) { "No printer address" }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** The candidate that answered as Moonraker, once one has. */
    @Volatile
    private var resolved: HttpUrl? = null

    /** The host as the user typed it, for messages and retries. */
    val host: String get() = candidates.first().host

    /** The base URL that answers as Moonraker, finding it the first time. */
    suspend fun base(): HttpUrl = resolved ?: find().first

    /** The first candidate that answers `server/info` as Moonraker, and its klippy state. */
    suspend fun find(): Pair<HttpUrl, String> {
        val failures = mutableListOf<Pair<HttpUrl, IOException>>()
        for (base in candidates) {
            try {
                val state = klippyState(base)
                resolved = base
                return base to state
            } catch (e: IOException) {
                failures += base to e
            }
        }
        val (firstBase, firstError) = failures.first()
        if (failures.size == 1) throw firstError
        val tried = failures.joinToString("; ") { (base, error) -> "${base.host}:${base.port}: ${error.message}" }
        throw IOException("No Moonraker found at ${firstBase.host} ($tried). Check the address and that Moonraker is running.", firstError)
    }

    private suspend fun klippyState(base: HttpUrl): String {
        val result = try {
            call(base, "server/info", "GET", null)
        } catch (e: NotMoonraker) {
            null
        }
        return ((result as? JsonObject)?.get("klippy_state") as? JsonPrimitive)?.contentOrNull
            ?: throw IOException("The host answered but does not look like Moonraker (no result.klippy_state)")
    }

    suspend fun get(path: String): JsonElement = call(base(), path, "GET", null)

    suspend fun post(path: String, body: RequestBody = EMPTY): JsonElement = call(base(), path, "POST", body)

    suspend fun delete(path: String): JsonElement = call(base(), path, "DELETE", null)

    /** Calls [path] (relative, with any query) and returns the answer's `result`. */
    private suspend fun call(base: HttpUrl, path: String, method: String, body: RequestBody?): JsonElement {
        val url = base.resolve(path) ?: throw IOException("Bad Moonraker path: $path")
        val request = Request.Builder().url(url).apiKey(apiKey).method(method, body).build()
        val text = http.newCall(request).await().bodyOrThrow()
        val answer = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw NotMoonraker()
        return answer["result"] ?: JsonNull
    }

    /** Sends G-code through Klipper (lines separated by newlines), as the console does. */
    suspend fun gcode(script: String) {
        post("printer/gcode/script?script=" + query(script))
    }

    /** [value] encoded for a query string. */
    fun query(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    /** An answer that isn't Moonraker's JSON. */
    private class NotMoonraker : IOException("The host answered but not as Moonraker")

    private companion object {
        val EMPTY = ByteArray(0).toRequestBody(null)
    }
}
