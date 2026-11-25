package com.orcamobile.core.printer.elegoo

import com.orcamobile.core.domain.printer.CameraSource
import com.orcamobile.core.domain.printer.FanControl
import com.orcamobile.core.domain.printer.GcodeConsole
import com.orcamobile.core.domain.printer.JobControl
import com.orcamobile.core.domain.printer.LightControl
import com.orcamobile.core.domain.printer.MotionControl
import com.orcamobile.core.domain.printer.PrintHistory
import com.orcamobile.core.domain.printer.PrinterConnection
import com.orcamobile.core.domain.printer.PrinterFiles
import com.orcamobile.core.domain.printer.TemperatureControl
import com.orcamobile.core.model.Axis
import com.orcamobile.core.model.BuildPlateSide
import com.orcamobile.core.model.CameraFeed
import com.orcamobile.core.model.FanKind
import com.orcamobile.core.model.HeaterKind
import com.orcamobile.core.model.MachineState
import com.orcamobile.core.model.PrintRecord
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.PrinterFile
import com.orcamobile.core.model.PrinterLink
import com.orcamobile.core.model.PrinterState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * A live connection to an Elegoo printer over SDCP v3 (`ws://<host>:3030/websocket`),
 * as Elegoo's own apps keep one: status and attributes as the printer pushes
 * them (and asked for every few seconds), a `ping` heartbeat, and commands
 * matched to their answers by request id. It reconnects on its own.
 */
internal class SdcpConnection(
    private val host: String,
    http: OkHttpClient,
    context: CoroutineContext,
    private val port: Int = ElegooLinkClient.SDCP_PORT,
    private val heartbeatMillis: Long = 10_000,
    private val refreshMillis: Long = 5_000,
    private val commandMillis: Long = 10_000,
) : PrinterConnection {
    // Pushes come at the printer's pace: no read timeout, the heartbeat finds a dead link.
    private val http = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val scope = CoroutineScope(context + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(PrinterState())
    override val state: StateFlow<PrinterState> = _state.asStateFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var mainboardId = ""
    @Volatile private var lastHeard = 0L

    init {
        scope.launch { keepConnected() }
    }

    // --- the link -------------------------------------------------------------

    private suspend fun keepConnected() {
        var backoff = MIN_BACKOFF_MILLIS
        while (scope.isActive) {
            val closed = CompletableDeferred<String?>()
            _state.update { if (it.link is PrinterLink.Online) it else it.copy(link = PrinterLink.Connecting) }
            val ws = http.newWebSocket(Request.Builder().url("http://$host:$port/websocket").build(), Listener(closed))
            socket = ws
            val reason = closed.await()
            socket = null
            failPending(reason)
            if (_state.value.link is PrinterLink.Online) backoff = MIN_BACKOFF_MILLIS
            _state.update { it.copy(link = PrinterLink.Offline(reason)) }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
        }
    }

    private inner class Listener(private val closed: CompletableDeferred<String?>) : WebSocketListener() {
        private var keepAlive: Job? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            lastHeard = System.currentTimeMillis()
            _state.update { it.copy(link = PrinterLink.Online) }
            keepAlive = scope.launch {
                launch { request(CMD_ATTRIBUTES) }
                launch { refreshCanvas() }
                var sinceHeartbeat = 0L
                while (isActive) {
                    send(webSocket, command(CMD_STATUS))
                    delay(refreshMillis)
                    sinceHeartbeat += refreshMillis
                    if (sinceHeartbeat >= heartbeatMillis) {
                        sinceHeartbeat = 0
                        webSocket.send("ping")
                        if (System.currentTimeMillis() - lastHeard > heartbeatMillis * 3) {
                            webSocket.cancel()
                            end("The printer stopped answering")
                        }
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastHeard = System.currentTimeMillis()
            if (text == "pong") return
            val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            handle(message)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            end("The printer closed the connection")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end("The printer closed the connection")

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            end(t.message?.let { "Couldn't reach the printer: $it" } ?: "Couldn't reach the printer")

        private fun end(reason: String) {
            keepAlive?.cancel()
            closed.complete(reason)
        }
    }

    /** One message from the printer: a status or attributes push, a command's answer, an error or a notice. */
    internal fun handle(message: JsonObject) {
        (message["MainboardID"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { mainboardId = it }
        val topic = (message["Topic"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        (message["Status"] as? JsonObject)?.let { status ->
            _state.update { SdcpStatus.applyStatus(status, it) }
            return
        }
        (message["Attributes"] as? JsonObject)?.let { attributes ->
            (attributes["MainboardID"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { mainboardId = it }
            _state.update { SdcpStatus.applyAttributes(attributes, it) }
            return
        }
        val envelope = message["Data"] as? JsonObject ?: return
        val data = envelope["Data"] as? JsonObject ?: JsonObject(emptyMap())
        when {
            topic.startsWith("sdcp/error") ->
                _state.update { it.copy(message = SdcpStatus.printError(data["ErrorCode"]?.jsonPrimitive?.intOrNull ?: -1)) }
            else -> {
                val id = (envelope["RequestID"] as? JsonPrimitive)?.contentOrNull ?: return
                pending.remove(id)?.complete(data)
                if (envelope["Cmd"]?.jsonPrimitive?.intOrNull == CMD_CANVAS && data["Ack"]?.jsonPrimitive?.intOrNull == 0) {
                    _state.update { SdcpStatus.applyCanvas(data, it) }
                }
            }
        }
    }

    // --- commands -------------------------------------------------------------

    private fun command(cmd: Int, requestId: String = newRequestId(), data: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            put("Id", "")
            putJsonObject("Data") {
                put("Cmd", cmd)
                putJsonObject("Data", data)
                put("RequestID", requestId)
                put("MainboardID", mainboardId)
                put("TimeStamp", System.currentTimeMillis() / 1000)
                put("From", FROM_APP)
            }
            put("Topic", "sdcp/request/$mainboardId")
        }

    private fun send(webSocket: WebSocket, message: JsonObject) {
        webSocket.send(message.toString())
    }

    /** Sends command [cmd] and waits for its answer's data; fails when the printer refuses it (a non-zero `Ack`). */
    private suspend fun request(
        cmd: Int,
        refusal: (Int) -> String = { "The printer refused the command (code $it)" },
        data: JsonObjectBuilder.() -> Unit = {},
    ): Result<JsonObject> = runCatching {
        val ws = socket ?: throw IOException("Not connected to the printer")
        val id = newRequestId()
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        try {
            if (!ws.send(command(cmd, id, data).toString())) throw IOException("Couldn't send to the printer")
            val reply = withTimeout(commandMillis) { answer.await() }
            val ack = reply["Ack"]?.jsonPrimitive?.intOrNull ?: 0
            if (ack != 0) throw IOException(refusal(ack))
            reply
        } finally {
            pending.remove(id)
        }
    }

    private fun failPending(reason: String?) {
        val error = IOException(reason ?: "Lost the connection to the printer")
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private suspend fun refreshCanvas() {
        request(CMD_CANVAS)
    }

    private fun newRequestId() = UUID.randomUUID().toString().replace("-", "")

    // --- what the printer can do ----------------------------------------------

    override val jobs: JobControl = object : JobControl {
        override suspend fun pause() = request(CMD_PAUSE).map { }
        override suspend fun resume() = request(CMD_RESUME).map { }
        override suspend fun stop() = request(CMD_STOP).map { }
    }

    override val temperatures: TemperatureControl = object : TemperatureControl {
        override val heaters = setOf(HeaterKind.NOZZLE, HeaterKind.BED)

        override suspend fun setTarget(heater: HeaterKind, celsius: Int) = when (heater) {
            HeaterKind.NOZZLE -> request(CMD_CONTROL) { put("TempTargetNozzle", celsius.coerceIn(0, NOZZLE_MAX)) }.map { }
            HeaterKind.BED -> request(CMD_CONTROL) { put("TempTargetHotbed", celsius.coerceIn(0, BED_MAX)) }.map { }
            HeaterKind.CHAMBER -> Result.failure(UnsupportedOperationException("The chamber has no heater to set"))
        }
    }

    override val fans: FanControl = object : FanControl {
        override val fans = setOf(FanKind.PART, FanKind.AUXILIARY, FanKind.CHAMBER)
        override val speedRange = SPEED_MIN..SPEED_MAX

        override suspend fun setFan(fan: FanKind, percent: Int) = request(CMD_CONTROL) {
            putJsonObject("TargetFanSpeed") {
                put(
                    when (fan) {
                        FanKind.PART -> "ModelFan"
                        FanKind.AUXILIARY -> "AuxiliaryFan"
                        FanKind.CHAMBER -> "BoxFan"
                    },
                    percent.coerceIn(0, 100),
                )
            }
        }.map { }

        override suspend fun setSpeed(percent: Int) =
            request(CMD_CONTROL) { put("PrintSpeedPct", percent.coerceIn(SPEED_MIN, SPEED_MAX)) }.map { }
    }

    override val motion: MotionControl = object : MotionControl {
        override suspend fun home(axes: Set<Axis>) = request(CMD_HOME) {
            put("Axis", if (axes.isEmpty() || axes.size == 3) "XYZ" else axes.sorted().joinToString("") { it.name })
        }.map { }

        // The Centauri Carbon's move command isn't documented well enough to send yet.
        override val canJog = false

        override suspend fun jog(axis: Axis, millimetres: Double) =
            Result.failure<Unit>(UnsupportedOperationException("Moving the axes isn't supported on this printer yet"))
    }

    override val light: LightControl = object : LightControl {
        override suspend fun setLight(on: Boolean) = request(CMD_CONTROL) {
            putJsonObject("LightStatus") {
                put("SecondLight", if (on) 1 else 0)
                putJsonArray("RgbLight") { repeat(3) { add(if (on) 255 else 0) } }
            }
        }.map { }
    }

    override val camera: CameraSource = object : CameraSource {
        override suspend fun open(): Result<CameraFeed> = request(CMD_VIDEO, refusal = ::videoRefusal) { put("Enable", 1) }.mapCatching { reply ->
            val url = reply["VideoUrl"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IOException("The printer didn't say where its camera stream is")
            CameraFeed.Mjpeg(if (url.startsWith("http")) url else "http://$url")
        }

        override suspend fun release() {
            request(CMD_VIDEO) { put("Enable", 0) }
        }
    }

    override val files: PrinterFiles = object : PrinterFiles {
        override suspend fun list(folder: String?) = request(CMD_FILES) { put("Url", folder ?: "/local/") }.map { reply ->
            (reply["FileList"] as? JsonArray).orEmpty().mapNotNull { element ->
                val file = element as? JsonObject ?: return@mapNotNull null
                val name = file["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val path = if (name.startsWith("/")) name else (folder ?: "/local/").trimEnd('/') + "/" + name
                PrinterFile(
                    path = path,
                    name = name.substringAfterLast('/'),
                    sizeBytes = file["usedSize"]?.jsonPrimitive?.longOrNull,
                    isFolder = file["type"]?.jsonPrimitive?.intOrNull == 0,
                )
            }
        }

        override suspend fun print(file: PrinterFile, options: PrintStartOptions) = request(CMD_START, refusal = { SdcpSession.startPrintError(it) }) {
            put("Filename", file.path)
            put("StartLayer", 0)
            put("Calibration_switch", if (options.heatedBedLeveling) 1 else 0)
            put("PrintPlatformType", if (options.plate == BuildPlateSide.SMOOTH) 1 else 0)
            put("Tlp_Switch", if (options.timeLapse) 1 else 0)
        }.map { }

        override suspend fun delete(files: List<PrinterFile>) = request(CMD_DELETE) {
            putJsonArray("FileList") { files.filterNot { it.isFolder }.forEach { add(it.path) } }
            putJsonArray("FolderList") { files.filter { it.isFolder }.forEach { add(it.path) } }
        }.map { }
    }

    override val history: PrintHistory = object : PrintHistory {
        override suspend fun recent() = request(CMD_HISTORY).mapCatching { reply ->
            val ids = (reply["HistoryData"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.takeLast(HISTORY_SIZE)
            if (ids.isEmpty()) return@mapCatching emptyList()
            val details = request(CMD_HISTORY_DETAILS) { putJsonArray("Id") { ids.forEach { add(it) } } }.getOrThrow()
            (details["HistoryDetailList"] as? JsonArray).orEmpty().mapNotNull { element ->
                val task = element as? JsonObject ?: return@mapNotNull null
                val outcome = when (task["TaskStatus"]?.jsonPrimitive?.intOrNull) {
                    1 -> MachineState.FINISHED
                    2 -> MachineState.ERROR
                    3 -> MachineState.CANCELLED
                    else -> MachineState.UNKNOWN
                }
                PrintRecord(
                    id = task["TaskId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    fileName = task["TaskName"]?.jsonPrimitive?.contentOrNull.orEmpty().substringAfterLast('/'),
                    startedEpochSeconds = task["BeginTime"]?.jsonPrimitive?.longOrNull,
                    endedEpochSeconds = task["EndTime"]?.jsonPrimitive?.longOrNull,
                    outcome = outcome,
                    reason = task["ErrorStatusReason"]?.jsonPrimitive?.intOrNull?.let(SdcpStatus::taskError),
                )
            }.sortedByDescending { it.startedEpochSeconds ?: 0 }
        }
    }

    // SDCP has no G-code console.
    override val console: GcodeConsole? = null

    override fun close() {
        socket?.close(NORMAL_CLOSURE, null)
        scope.cancel()
    }

    internal companion object {
        const val CMD_STATUS = 0
        const val CMD_ATTRIBUTES = 1
        const val CMD_START = 128
        const val CMD_PAUSE = 129
        const val CMD_STOP = 130
        const val CMD_RESUME = 131
        const val CMD_FILES = 258
        const val CMD_DELETE = 259
        const val CMD_HISTORY = 320
        const val CMD_HISTORY_DETAILS = 321
        const val CMD_CANVAS = 324
        const val CMD_VIDEO = 386
        const val CMD_HOME = 402
        const val CMD_CONTROL = 403

        /** SDCP's "From" for an app. */
        private const val FROM_APP = 3
        private const val NORMAL_CLOSURE = 1000
        private const val MIN_BACKOFF_MILLIS = 2_000L
        private const val MAX_BACKOFF_MILLIS = 30_000L
        private const val NOZZLE_MAX = 320
        private const val BED_MAX = 110
        private const val SPEED_MIN = 50
        private const val SPEED_MAX = 160
        private const val HISTORY_SIZE = 20

        fun videoRefusal(ack: Int) = when (ack) {
            1 -> "Too many people are watching the camera. Close it elsewhere and try again."
            2 -> "The printer has no camera."
            else -> "The camera couldn't start (code $ack)."
        }
    }
}
