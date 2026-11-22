package com.orcamobile.core.printer.elegoo

import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostInfo
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.printer.http.apiKey
import com.orcamobile.core.printer.http.await
import com.orcamobile.core.printer.http.bodyOrThrow
import com.orcamobile.core.printer.http.normalizeBaseUrl
import com.orcamobile.core.printer.http.withNetworkRetries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/**
 * Elegoo Centauri Carbon over ElegooLink, as desktop OrcaSlicer's
 * src/slic3r/Utils/ElegooLink.cpp does for it:
 * - test: `GET /`, whose page names ELEGOO;
 * - upload: `POST /uploadFile/upload` in 1 MiB parts, each with the file's MD5,
 *   an upload id, its offset and the total size; the printer answers code `000000`;
 * - start: an SDCP start-print command over the printer's WebSocket ([SdcpSession]).
 *
 * The Centauri Carbon 2 uses a different API (token, `/system/info`, `/upload`)
 * and is not covered yet.
 */
internal class ElegooLinkClient(
    config: PrintHostConfig,
    private val http: OkHttpClient,
    private val sdcpPort: Int = SDCP_PORT,
    /** Pause before each SDCP step, as desktop OrcaSlicer waits; tests shorten it. */
    private val sdcpSettleMillis: Long = 1_000L,
) : PrintHostClient {
    private val baseUrl: HttpUrl = normalizeBaseUrl(config.baseUrl)
    private val apiKey = config.apiKey
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun testConnection(): Result<PrintHostInfo> = runCatching {
        val request = Request.Builder().url(baseUrl).apiKey(apiKey).get().build()
        val page = withNetworkRetries(baseUrl.host) { http.newCall(request).await().bodyOrThrow() }
        if (!page.contains("elegoo", ignoreCase = true)) {
            throw IOException("The host answered but is not an Elegoo printer (ElegooLink not detected)")
        }
        PrintHostInfo("Elegoo printer (ElegooLink)")
    }

    override suspend fun upload(
        localPath: String,
        remoteName: String,
        startPrint: Boolean,
        options: PrintStartOptions,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): Result<Unit> = runCatching {
        val file = File(localPath)
        val total = file.length()
        val md5 = md5Hex(file)
        val uuid = UUID.randomUUID().toString()
        RandomAccessFile(file, "r").use { input ->
            var offset = 0L
            do {
                val length = minOf(PART_BYTES.toLong(), total - offset).toInt()
                val part = ByteArray(length)
                input.seek(offset)
                input.readFully(part)
                uploadPart(part, md5, uuid, remoteName, offset, total)
                offset += length
                onProgress(offset, total)
            } while (offset < total)
        }
        if (startPrint) {
            SdcpSession(http, baseUrl.host, sdcpPort).use { it.startPrint(remoteName, options, sdcpSettleMillis) }
        }
    }

    private suspend fun uploadPart(part: ByteArray, md5: String, uuid: String, name: String, offset: Long, total: Long) {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("Check", "1")
            .addFormDataPart("S-File-MD5", md5)
            .addFormDataPart("Offset", offset.toString())
            .addFormDataPart("Uuid", uuid)
            .addFormDataPart("TotalSize", total.toString())
            .addFormDataPart("File", name, part.toRequestBody(OCTET_STREAM))
            .build()
        val request = Request.Builder().url(baseUrl.resolve("uploadFile/upload")!!).apiKey(apiKey).post(form).build()
        val body = http.newCall(request).await().bodyOrThrow()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw IOException("The printer's upload answer could not be read: ${body.take(200)}")
        val code = root["code"]?.jsonPrimitive?.contentOrNull
        if (code != SUCCESS_CODE) {
            val messages = (root["messages"] as? JsonArray).orEmpty().joinToString("; ") { message ->
                val fields = message.jsonObject
                listOfNotNull(fields["field"]?.jsonPrimitive?.contentOrNull, fields["message"]?.jsonPrimitive?.contentOrNull)
                    .joinToString(": ")
            }
            throw IOException("The printer refused the upload (code $code)${if (messages.isBlank()) "" else ": $messages"}")
        }
    }

    internal companion object {
        /** The WebSocket port of the printer's SDCP service. */
        const val SDCP_PORT = 3030

        /** As desktop OrcaSlicer's MAX_UPLOAD_PACKAGE_LENGTH. */
        const val PART_BYTES = 1024 * 1024
        private const val SUCCESS_CODE = "000000"
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        /** Upper-case hex, as the printer compares it. */
        fun md5Hex(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02X".format(it) }
        }
    }
}
