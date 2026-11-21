package com.orcamobile.core.printer.moonraker

import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostInfo
import com.orcamobile.core.printer.http.ProgressRequestBody
import com.orcamobile.core.printer.http.apiKey
import com.orcamobile.core.printer.http.await
import com.orcamobile.core.printer.http.bodyOrThrow
import com.orcamobile.core.printer.http.normalizeBaseUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Klipper printers through Moonraker's HTTP API, as desktop OrcaSlicer's
 * src/slic3r/Utils/Moonraker.cpp does: `GET /server/info` to test, and
 * `POST /server/files/upload` (root `gcodes`, optional `print=true`) to send.
 */
internal class MoonrakerClient(
    config: PrintHostConfig,
    private val http: OkHttpClient,
) : PrintHostClient {
    private val baseUrl = normalizeBaseUrl(config.baseUrl)
    private val apiKey = config.apiKey
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun testConnection(): Result<PrintHostInfo> = runCatching {
        val request = Request.Builder().url(baseUrl.resolve("server/info")!!).apiKey(apiKey).get().build()
        val body = http.newCall(request).await().bodyOrThrow()
        val result = json.parseToJsonElement(body).jsonObject["result"]?.jsonObject
        val state = result?.get("klippy_state")?.jsonPrimitive?.contentOrNull
            ?: throw IOException("The host answered but does not look like Moonraker (no result.klippy_state)")
        PrintHostInfo("Klipper $state")
    }

    override suspend fun upload(
        localPath: String,
        remoteName: String,
        startPrint: Boolean,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): Result<Unit> = runCatching {
        val file = File(localPath)
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("root", "gcodes")
            .addFormDataPart("print", startPrint.toString())
            .addFormDataPart("file", remoteName, ProgressRequestBody(file, onProgress))
            .build()
        val request = Request.Builder().url(baseUrl.resolve("server/files/upload")!!).apiKey(apiKey).post(form).build()
        http.newCall(request).await().bodyOrThrow()
        Unit
    }
}
