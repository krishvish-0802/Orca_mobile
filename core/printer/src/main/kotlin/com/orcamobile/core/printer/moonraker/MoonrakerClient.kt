package com.orcamobile.core.printer.moonraker

import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostInfo
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.printer.http.ProgressRequestBody
import com.orcamobile.core.printer.http.apiKey
import com.orcamobile.core.printer.http.await
import com.orcamobile.core.printer.http.bodyOrThrow
import com.orcamobile.core.printer.http.hasExplicitPort
import com.orcamobile.core.printer.http.normalizeBaseUrl
import com.orcamobile.core.printer.http.withNetworkRetries
import java.io.File
import okhttp3.HttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Klipper printers through Moonraker's HTTP API, as desktop OrcaSlicer's
 * src/slic3r/Utils/Moonraker.cpp does: `GET /server/info` to test, and
 * `POST /server/files/upload` (root `gcodes`, optional `print=true`) to send.
 *
 * An address without a port is tried as typed (port 80, where a web UI may pass
 * the API through), then on Moonraker's own port, [MOONRAKER_PORT]: on many
 * printers, such as Elegoo's Neptune 4 series, port 80 serves only Fluidd or
 * Mainsail and answers the API with 404.
 */
internal class MoonrakerClient(
    private val api: MoonrakerApi,
    private val apiKey: String?,
    private val http: OkHttpClient,
) : PrintHostClient {
    constructor(config: PrintHostConfig, http: OkHttpClient) : this(moonrakerCandidates(config.baseUrl), config.apiKey, http)

    /** Tries [candidates] in order. */
    constructor(candidates: List<HttpUrl>, apiKey: String?, http: OkHttpClient) : this(MoonrakerApi(candidates, apiKey, http), apiKey, http)

    override suspend fun testConnection(): Result<PrintHostInfo> = runCatching {
        val (_, state) = withNetworkRetries(api.host) { api.find() }
        PrintHostInfo("Klipper $state")
    }

    override suspend fun upload(
        localPath: String,
        remoteName: String,
        startPrint: Boolean,
        options: PrintStartOptions,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): Result<Unit> = runCatching {
        val baseUrl = api.base()
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

/** Moonraker's default HTTP port. */
internal const val MOONRAKER_PORT = 7125

/** The address as typed, then on [MOONRAKER_PORT] if the user gave no port. */
internal fun moonrakerCandidates(raw: String): List<HttpUrl> {
    val typed = normalizeBaseUrl(raw)
    if (hasExplicitPort(raw) || typed.port == MOONRAKER_PORT) return listOf(typed)
    return listOf(typed, typed.newBuilder().port(MOONRAKER_PORT).build())
}
