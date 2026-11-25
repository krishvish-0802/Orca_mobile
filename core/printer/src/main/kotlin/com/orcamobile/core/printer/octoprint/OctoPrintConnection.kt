package com.orcamobile.core.printer.octoprint

import com.orcamobile.core.domain.printer.CameraSource
import com.orcamobile.core.domain.printer.FanControl
import com.orcamobile.core.domain.printer.GcodeConsole
import com.orcamobile.core.domain.printer.JobControl
import com.orcamobile.core.domain.printer.LightControl
import com.orcamobile.core.domain.printer.MotionControl
import com.orcamobile.core.domain.printer.PrintHistory
import com.orcamobile.core.domain.printer.PrinterFiles
import com.orcamobile.core.domain.printer.TemperatureControl
import com.orcamobile.core.model.Axis
import com.orcamobile.core.model.CameraFeed
import com.orcamobile.core.model.FanKind
import com.orcamobile.core.model.Heater
import com.orcamobile.core.model.HeaterKind
import com.orcamobile.core.model.MachineState
import com.orcamobile.core.model.PrintJob
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.PrinterFile
import com.orcamobile.core.model.PrinterState
import com.orcamobile.core.printer.http.PollingConnection
import com.orcamobile.core.printer.http.PrintHostHttpException
import com.orcamobile.core.printer.http.apiKey
import com.orcamobile.core.printer.http.await
import com.orcamobile.core.printer.http.bodyOrThrow
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A printer through OctoPrint's REST API (docs.octoprint.org, "REST API"):
 * `api/printer` and `api/job` read every [intervalMillis]; job, tool, bed,
 * print head and command endpoints to control it.
 */
internal class OctoPrintConnection(
    private val base: HttpUrl,
    private val key: String?,
    private val http: OkHttpClient,
    context: CoroutineContext,
    intervalMillis: Long = 2_000,
) : PollingConnection(context, intervalMillis) {

    private val json = Json { ignoreUnknownKeys = true }

    /** OctoPrint's version, read once. */
    private var version: String? = null

    override suspend fun read(previous: PrinterState): PrinterState {
        if (version == null) version = runCatching { (fetch("api/version") as? JsonObject)?.text("text") }.getOrNull() ?: ""
        val printer = try {
            fetch("api/printer") as? JsonObject
        } catch (e: PrintHostHttpException) {
            // 409: OctoPrint runs, but isn't connected to the printer.
            if (e.status != 409) throw e
            return previous.copy(machine = MachineState.UNKNOWN, job = null, heaters = emptyList(), message = NOT_CONNECTED, firmware = version)
        }
        val job = fetch("api/job") as? JsonObject
        return OctoPrintStatus.apply(printer, job, previous).copy(firmware = version?.ifEmpty { null })
    }

    override val jobs = object : JobControl {
        override suspend fun pause() = command { post("api/job", buildJsonObject { put("command", "pause"); put("action", "pause") }) }

        override suspend fun resume() = command { post("api/job", buildJsonObject { put("command", "pause"); put("action", "resume") }) }

        override suspend fun stop() = command { post("api/job", buildJsonObject { put("command", "cancel") }) }
    }

    override val temperatures = object : TemperatureControl {
        override val heaters: Set<HeaterKind>
            get() = state.value.heaters.map { it.kind }.toSet().ifEmpty { setOf(HeaterKind.NOZZLE, HeaterKind.BED) }

        override suspend fun setTarget(heater: HeaterKind, celsius: Int) = command {
            val target = celsius.coerceAtLeast(0)
            when (heater) {
                HeaterKind.NOZZLE -> post("api/printer/tool", buildJsonObject { put("command", "target"); putJsonObject("targets") { put("tool0", target) } })
                HeaterKind.BED -> post("api/printer/bed", buildJsonObject { put("command", "target"); put("target", target) })
                HeaterKind.CHAMBER -> post("api/printer/chamber", buildJsonObject { put("command", "target"); put("target", target) })
            }
        }
    }

    override val fans = object : FanControl {
        override val fans = setOf(FanKind.PART)

        override suspend fun setFan(fan: FanKind, percent: Int) = command {
            require(fan == FanKind.PART) { "Only the part cooling fan can be set here" }
            gcode("M106 S${(percent.coerceIn(0, 100) * 255 / 100.0).roundToInt()}")
        }

        // OctoPrint's own range for the feed rate factor.
        override val speedRange = 50..200

        override suspend fun setSpeed(percent: Int) = command {
            post("api/printer/printhead", buildJsonObject { put("command", "feedrate"); put("factor", percent.coerceIn(speedRange)) })
        }
    }

    override val motion = object : MotionControl {
        override suspend fun home(axes: Set<Axis>) = command {
            val homed = axes.ifEmpty { Axis.entries.toSet() }.sorted()
            post("api/printer/printhead", buildJsonObject { put("command", "home"); putJsonArray("axes") { homed.forEach { add(JsonPrimitive(it.name.lowercase())) } } })
        }

        override val canJog = true

        override suspend fun jog(axis: Axis, millimetres: Double) = command {
            post("api/printer/printhead", buildJsonObject { put("command", "jog"); put(axis.name.lowercase(), millimetres) })
        }
    }

    override val light: LightControl? = null

    override val camera = object : CameraSource {
        override suspend fun open(): Result<CameraFeed> = runCatching {
            val settings = runCatching { fetch("api/settings") as? JsonObject }.getOrNull()
            cameraFeed(settings, base)
        }

        override suspend fun release() = Unit
    }

    override val files = object : PrinterFiles {
        override suspend fun list(folder: String?) = runCatching {
            val all = OctoPrintStatus.files(fetch("api/files/local?recursive=true"))
            all.filter { folder == null || it.path.startsWith("$folder/") }.sortedBy { it.path.lowercase() }
        }

        override suspend fun print(file: PrinterFile, options: PrintStartOptions) =
            command { post("api/files/local/${encodePath(file.path)}", buildJsonObject { put("command", "select"); put("print", true) }) }

        override suspend fun delete(files: List<PrinterFile>) = command {
            for (file in files) call("api/files/local/${encodePath(file.path)}", "DELETE", null)
        }
    }

    // OctoPrint keeps no print history itself (a plugin does).
    override val history: PrintHistory? = null

    override val console = object : GcodeConsole {
        override suspend fun send(gcode: String) = command { gcode(gcode) }

        // The terminal's answers come only over OctoPrint's push socket.
        override val output: Flow<String>? = null
    }

    private suspend fun gcode(lines: String) {
        post("api/printer/command", buildJsonObject { putJsonArray("commands") { lines.lines().filter { it.isNotBlank() }.forEach { add(JsonPrimitive(it.trim())) } } })
    }

    private suspend fun fetch(path: String): JsonElement? = call(path, "GET", null)

    private suspend fun post(path: String, body: JsonObject): JsonElement? = call(path, "POST", body)

    /** Calls [path] and returns its JSON answer; null when it answers with nothing (204). */
    private suspend fun call(path: String, method: String, body: JsonObject?): JsonElement? {
        val url = base.resolve(path) ?: throw IOException("Bad OctoPrint path: $path")
        val request = Request.Builder().url(url).apiKey(key).method(method, body?.toString()?.toRequestBody(JSON)).build()
        val text = http.newCall(request).await().bodyOrThrow()
        return if (text.isBlank()) null else json.parseToJsonElement(text)
    }

    private fun encodePath(path: String) = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull

    internal companion object {
        private val JSON = "application/json".toMediaType()
        const val NOT_CONNECTED = "OctoPrint isn't connected to the printer"

        /** OctoPrint's camera, from its settings: the classic webcam plugin's (1.9 and later) or the core's. */
        fun cameraFeed(settings: JsonObject?, base: HttpUrl): CameraFeed {
            val plugin = ((settings?.get("plugins") as? JsonObject)?.get("classicwebcam") as? JsonObject)
            val core = settings?.get("webcam") as? JsonObject
            fun text(owner: JsonObject?, name: String) = (owner?.get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
            val stream = text(plugin, "stream") ?: text(core, "streamUrl")
            val snapshot = text(plugin, "snapshot") ?: text(core, "snapshotUrl")
            fun resolve(url: String) = base.resolve(url.removePrefix("/"))?.toString() ?: url
            return when {
                stream != null -> CameraFeed.Mjpeg(resolve(stream))
                snapshot != null -> CameraFeed.Snapshots(resolve(snapshot))
                else -> CameraFeed.Mjpeg(resolve("webcam/?action=stream"))
            }
        }
    }
}

/** OctoPrint's printer and job answers, read into a [PrinterState]. */
internal object OctoPrintStatus {

    fun apply(printer: JsonObject?, job: JsonObject?, previous: PrinterState): PrinterState {
        val state = printer?.obj("state")
        val flags = state?.obj("flags")
        fun flag(name: String) = (flags?.get(name) as? JsonPrimitive)?.booleanOrNull == true
        val text = (state?.get("text") as? JsonPrimitive)?.contentOrNull
        val machine = when {
            flag("cancelling") -> MachineState.STOPPING
            flag("pausing") -> MachineState.PAUSING
            flag("paused") -> MachineState.PAUSED
            flag("printing") -> MachineState.PRINTING
            flag("error") || flag("closedOrError") -> MachineState.ERROR
            flag("operational") -> MachineState.IDLE
            else -> MachineState.UNKNOWN
        }
        val temperature = printer?.obj("temperature")
        val heaters = listOfNotNull(
            temperature?.heater("tool0", HeaterKind.NOZZLE),
            temperature?.heater("bed", HeaterKind.BED),
            temperature?.heater("chamber", HeaterKind.CHAMBER),
        )
        return previous.copy(
            machine = machine,
            job = job?.let { job(it, machine) },
            heaters = heaters,
            message = text?.takeIf { machine == MachineState.ERROR },
        )
    }

    private fun job(answer: JsonObject, machine: MachineState): PrintJob? {
        if (machine !in ACTIVE) return null
        val file = answer.obj("job")?.obj("file")
        val name = (file?.get("display") ?: file?.get("name"))?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return null
        val progress = answer.obj("progress")
        return PrintJob(
            fileName = name,
            progress = progress?.number("completion")?.let { (it / 100).coerceIn(0.0, 1.0) },
            elapsedSeconds = progress?.number("printTime")?.roundToLong(),
            remainingSeconds = progress?.number("printTimeLeft")?.roundToLong(),
        )
    }

    /** `api/files/local?recursive=true`: the files under every folder, flattened. */
    fun files(answer: JsonElement?): List<PrinterFile> {
        val out = mutableListOf<PrinterFile>()
        fun walk(entries: JsonArray?) {
            for (entry in entries.orEmpty().filterIsInstance<JsonObject>()) {
                val type = (entry["type"] as? JsonPrimitive)?.contentOrNull
                if (type == "folder") {
                    walk(entry["children"] as? JsonArray)
                    continue
                }
                val path = (entry["path"] as? JsonPrimitive)?.contentOrNull ?: continue
                val name = (entry["display"] as? JsonPrimitive)?.contentOrNull ?: path.substringAfterLast('/')
                out += PrinterFile(path, name, entry.number("size")?.toLong())
            }
        }
        walk((answer as? JsonObject)?.get("files") as? JsonArray)
        return out
    }

    private val ACTIVE = setOf(MachineState.PRINTING, MachineState.PAUSING, MachineState.PAUSED, MachineState.STOPPING)

    private fun JsonObject.heater(name: String, kind: HeaterKind): Heater? {
        val heater = obj(name) ?: return null
        val actual = heater.number("actual") ?: return null
        return Heater(kind, actual, heater.number("target") ?: 0.0)
    }

    private fun JsonObject.obj(name: String) = get(name) as? JsonObject

    private fun JsonObject.number(name: String) = (get(name) as? JsonPrimitive)?.doubleOrNull
}
