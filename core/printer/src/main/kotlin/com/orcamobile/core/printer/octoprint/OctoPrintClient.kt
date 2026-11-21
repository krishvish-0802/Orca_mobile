package com.orcamobile.core.printer.octoprint

import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostInfo
import com.orcamobile.core.model.PrintStartOptions
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
 * OctoPrint's REST API, as desktop OrcaSlicer's src/slic3r/Utils/OctoPrint.cpp
 * does: `GET /api/version` to test, `POST /api/files/local` (with `select` and
 * `print`) to send.
 */
internal class OctoPrintClient(
    config: PrintHostConfig,
    private val http: OkHttpClient,
) : PrintHostClient {
    private val baseUrl = normalizeBaseUrl(config.baseUrl)
    private val apiKey = config.apiKey
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun testConnection(): Result<PrintHostInfo> = runCatching {
        val request = Request.Builder().url(baseUrl.resolve("api/version")!!).apiKey(apiKey).get().build()
        val body = http.newCall(request).await().bodyOrThrow()
        val version = json.parseToJsonElement(body).jsonObject
        val text = version["text"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("The host answered but does not look like OctoPrint (no version text)")
        PrintHostInfo(text)
    }

    override suspend fun upload(
        localPath: String,
        remoteName: String,
        startPrint: Boolean,
        options: PrintStartOptions,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): Result<Unit> = runCatching {
        val file = File(localPath)
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("select", startPrint.toString())
            .addFormDataPart("print", startPrint.toString())
            .addFormDataPart("file", remoteName, ProgressRequestBody(file, onProgress))
            .build()
        val request = Request.Builder().url(baseUrl.resolve("api/files/local")!!).apiKey(apiKey).post(form).build()
        http.newCall(request).await().bodyOrThrow()
        Unit
    }
}
