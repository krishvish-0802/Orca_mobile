package com.orcamobile.core.printer.elegoo

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.io.IOException
import java.util.UUID

/**
 * One connection to an Elegoo printer's SDCP service (`ws://<host>:<port>/websocket`),
 * used as desktop OrcaSlicer's ElegooLink::checkResult and ElegooLink::print do:
 * wait until the printer has finished checking the uploaded file (status 8),
 * then send the start-print command and read its acknowledgement.
 */
internal class SdcpSession(http: OkHttpClient, host: String, port: Int) : Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    private val incoming = Channel<Result<String>>(Channel.UNLIMITED)
    private val opened = Channel<Result<Unit>>(Channel.CONFLATED)

    private val socket: WebSocket = http.newWebSocket(
        Request.Builder().url("http://$host:$port/websocket").build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.trySend(Result.success(Unit))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.trySend(Result.success(text))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = IOException("Lost the connection to the printer's start-print service: ${t.message}", t)
                opened.trySend(Result.failure(error))
                incoming.trySend(Result.failure(error))
            }
        },
    )

    /** Starts printing the uploaded file [fileName] (as uploaded, without a folder). */
    suspend fun startPrint(fileName: String, settleMillis: Long = SETTLE_MILLIS) {
        val connection = withTimeoutOrNull(CONNECT_MILLIS) { opened.receive() }
        if (connection == null || connection.isFailure) {
            throw IOException(
                "The file was uploaded, but the printer's start-print service could not be reached. Start it on the printer.",
                connection?.exceptionOrNull(),
            )
        }
        delay(settleMillis)
        waitUntilFileChecked(settleMillis)
        delay(settleMillis)
        send(command(CMD_START_PRINT) {
            put("Filename", "/local/$fileName")
            put("StartLayer", 0)
            put("Calibration_switch", 0)
            put("PrintPlatformType", 0)
            put("Tlp_Switch", 0)
        })
        val ack = withTimeoutOrNull(START_TIMEOUT_MILLIS) {
            var ack: Int? = null
            while (ack == null) {
                val data = receiveJson()["Data"] as? JsonObject ?: continue
                if (data["Cmd"]?.jsonPrimitive?.intOrNull != CMD_START_PRINT) continue
                ack = (data["Data"] as? JsonObject)?.get("Ack")?.jsonPrimitive?.intOrNull
                    ?: throw IOException("The printer answered the start-print command without a result")
            }
            ack
        } ?: throw IOException("The file was uploaded, but the printer did not confirm the start of the print")
        if (ack != ACK_OK) throw IOException("${startPrintError(ack)} (error code $ack)")
    }

    /** Status 8 means the printer is still checking the uploaded file. */
    private suspend fun waitUntilFileChecked(pollMillis: Long) {
        val checked = withTimeoutOrNull(CHECK_TIMEOUT_MILLIS) {
            send(command(CMD_GET_STATUS) {})
            while (STATUS_CHECKING_FILE in receiveStatus()) {
                delay(pollMillis)
                send(command(CMD_GET_STATUS) {})
            }
            true
        }
        if (checked == null) throw IOException("The file was uploaded, but the printer took too long to check it")
    }

    /** The next status report's CurrentStatus codes, skipping other messages. */
    private suspend fun receiveStatus(): List<Int> {
        while (true) {
            val status = receiveJson()["Status"] as? JsonObject ?: continue
            val current = status["CurrentStatus"] as? JsonArray ?: return emptyList()
            return current.mapNotNull { it.jsonPrimitive.intOrNull }
        }
    }

    private suspend fun receiveJson(): JsonObject {
        val text = incoming.receive().getOrThrow()
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }

    private fun send(message: JsonObject) {
        if (!socket.send(message.toString())) throw IOException("Could not send to the printer")
    }

    private fun command(cmd: Int, data: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("Id", "")
        putJsonObject("Data") {
            put("Cmd", cmd)
            putJsonObject("Data", data)
            put("RequestID", UUID.randomUUID().toString().replace("-", ""))
            put("MainboardID", "")
            put("TimeStamp", System.currentTimeMillis())
            put("From", 1)
        }
    }

    override fun close() {
        socket.close(NORMAL_CLOSURE, null)
    }

    internal companion object {
        const val CMD_GET_STATUS = 0
        const val CMD_START_PRINT = 128
        const val STATUS_CHECKING_FILE = 8
        const val ACK_OK = 0
        private const val NORMAL_CLOSURE = 1000
        private const val SETTLE_MILLIS = 1_000L
        private const val CONNECT_MILLIS = 10_000L
        private const val CHECK_TIMEOUT_MILLIS = 60_000L
        private const val START_TIMEOUT_MILLIS = 30_000L

        /** The printer's start-print acknowledgements, worded as desktop OrcaSlicer does. */
        fun startPrintError(ack: Int): String = when (ack) {
            1 -> "The printer is busy. The file is on the printer; start it there when it is free"
            2 -> "The printer could not find the uploaded file. Try again"
            3 -> "The uploaded file is corrupted (MD5 check failed). Try again"
            4, 5, 6 -> "The transfer went wrong. Try again"
            7 -> "The file does not match this printer. Slice it with the Centauri Carbon profile"
            else -> "The printer refused to start the print"
        }
    }
}
