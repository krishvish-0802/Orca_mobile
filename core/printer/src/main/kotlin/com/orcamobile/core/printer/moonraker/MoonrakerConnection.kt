package com.orcamobile.core.printer.moonraker

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
import com.orcamobile.core.model.HeaterKind
import com.orcamobile.core.model.PrintRecord
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.PrinterFile
import com.orcamobile.core.model.PrinterState
import com.orcamobile.core.printer.http.PollingConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import okhttp3.HttpUrl

/**
 * A Klipper printer through Moonraker, as Mainsail and Fluidd drive it: its
 * printer objects read every [intervalMillis] (`printer/objects/query`), the
 * print endpoints to pause, resume and cancel, and G-code for the rest
 * (temperatures, fans, speed, homing, jogging), as Klipper's console takes it.
 */
internal class MoonrakerConnection(
    private val api: MoonrakerApi,
    context: CoroutineContext,
    intervalMillis: Long = 2_000,
) : PollingConnection(context, intervalMillis) {

    /** Klipper's name and version, read once. */
    private var identity: Pair<String?, String?>? = null

    /** Whether the printer has a chamber heater (not just a sensor), as last read. */
    @Volatile
    private var chamberHeater = false

    override suspend fun read(previous: PrinterState): PrinterState {
        val (model, firmware) = identity ?: identify().also { identity = it }
        val result = api.get("printer/objects/query?${MoonrakerStatus.QUERY}") as? JsonObject
        val status = result?.get("status") as? JsonObject ?: throw IOException("Moonraker sent no printer status")
        chamberHeater = (status["heater_generic chamber"] as? JsonObject)?.containsKey("target") == true
        return MoonrakerStatus.apply(status, previous).copy(model = model, firmware = firmware)
    }

    /** The host's name and Klipper's version (`printer/info`); nulls when it doesn't say. */
    private suspend fun identify(): Pair<String?, String?> = runCatching {
        val info = api.get("printer/info") as? JsonObject
        info?.text("hostname") to info?.text("software_version")
    }.getOrDefault(null to null)

    override val jobs = object : JobControl {
        override suspend fun pause() = command { api.post("printer/print/pause") }

        override suspend fun resume() = command { api.post("printer/print/resume") }

        override suspend fun stop() = command { api.post("printer/print/cancel") }
    }

    override val temperatures = object : TemperatureControl {
        override val heaters: Set<HeaterKind>
            get() = if (chamberHeater) setOf(HeaterKind.NOZZLE, HeaterKind.BED, HeaterKind.CHAMBER) else setOf(HeaterKind.NOZZLE, HeaterKind.BED)

        override suspend fun setTarget(heater: HeaterKind, celsius: Int) = command {
            val name = when (heater) {
                HeaterKind.NOZZLE -> "extruder"
                HeaterKind.BED -> "heater_bed"
                HeaterKind.CHAMBER -> "chamber"
            }
            api.gcode("SET_HEATER_TEMPERATURE HEATER=$name TARGET=${celsius.coerceAtLeast(0)}")
        }
    }

    override val fans = object : FanControl {
        override val fans = setOf(FanKind.PART)

        override suspend fun setFan(fan: FanKind, percent: Int) = command {
            require(fan == FanKind.PART) { "Only the part cooling fan can be set here" }
            api.gcode("M106 S${(percent.coerceIn(0, 100) * 255 / 100.0).roundToInt()}")
        }

        override val speedRange = 10..300

        override suspend fun setSpeed(percent: Int) = command { api.gcode("M220 S${percent.coerceIn(speedRange)}") }
    }

    override val motion = object : MotionControl {
        override suspend fun home(axes: Set<Axis>) = command { api.gcode(if (axes.isEmpty()) "G28" else "G28 " + axes.sorted().joinToString(" ")) }

        override val canJog = true

        override suspend fun jog(axis: Axis, millimetres: Double) = command {
            val feed = if (axis == Axis.Z) Z_FEED else XY_FEED
            // Relative for the move only, as Mainsail's jog buttons do.
            api.gcode("SAVE_GCODE_STATE NAME=orca_jog\nG91\nG1 $axis$millimetres F$feed\nRESTORE_GCODE_STATE NAME=orca_jog")
        }
    }

    override val light: LightControl? = null

    override val camera = object : CameraSource {
        override suspend fun open(): Result<CameraFeed> = runCatching {
            val webcams = runCatching { (api.get("server/webcams/list") as? JsonObject)?.get("webcams") as? JsonArray }.getOrNull()
                .orEmpty().filterIsInstance<JsonObject>()
                .filter { (it["enabled"] as? JsonPrimitive)?.contentOrNull != "false" }
            cameraFeed(webcams.firstOrNull(), api.base())
        }

        override suspend fun release() = Unit
    }

    override val files = object : PrinterFiles {
        override suspend fun list(folder: String?) = runCatching {
            MoonrakerStatus.files(api.get("server/files/list?root=gcodes"))
                .filter { folder == null || it.path.startsWith("$folder/") }
                .sortedBy { it.path.lowercase() }
        }

        override suspend fun print(file: PrinterFile, options: PrintStartOptions) =
            command { api.post("printer/print/start?filename=${api.query(file.path)}") }

        override suspend fun delete(files: List<PrinterFile>) = command {
            for (file in files) api.delete("server/files/gcodes/${file.path.split('/').joinToString("/") { api.query(it) }}")
        }
    }

    override val history = object : PrintHistory {
        override suspend fun recent(): Result<List<PrintRecord>> = runCatching {
            MoonrakerStatus.history(api.get("server/history/list?limit=$HISTORY_SIZE&order=desc"))
        }
    }

    override val console = object : GcodeConsole {
        override suspend fun send(gcode: String) = command { api.gcode(gcode) }

        /** Klipper's console, as Moonraker keeps it (`server/gcode_store`): new lines as they come. */
        override val output: Flow<String> = flow {
            var since = Double.NEGATIVE_INFINITY
            while (true) {
                val store = runCatching { (api.get("server/gcode_store?count=$CONSOLE_LINES") as? JsonObject)?.get("gcode_store") as? JsonArray }.getOrNull()
                for (line in store.orEmpty().filterIsInstance<JsonObject>()) {
                    val time = (line["time"] as? JsonPrimitive)?.doubleOrNull ?: continue
                    if (time <= since) continue
                    since = time
                    line.text("message")?.let { emit(it) }
                }
                delay(CONSOLE_POLL_MILLIS)
            }
        }
    }

    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull

    internal companion object {
        private const val XY_FEED = 6_000
        private const val Z_FEED = 600
        private const val HISTORY_SIZE = 20
        private const val CONSOLE_LINES = 100
        private const val CONSOLE_POLL_MILLIS = 1_000L

        /** Where crowsnest serves the camera when Moonraker lists none. */
        private const val DEFAULT_STREAM = "webcam/?action=stream"

        /**
         * The camera [webcam] describes, its paths taken from the web server in
         * front of Moonraker: port 80 when the API was found on Moonraker's own port.
         */
        fun cameraFeed(webcam: JsonObject?, apiBase: HttpUrl): CameraFeed {
            val web = if (apiBase.port == MOONRAKER_PORT) apiBase.newBuilder().port(80).build() else apiBase
            fun resolve(path: String?) = path?.trim()?.takeIf { it.isNotEmpty() }?.let { web.resolve(it.removePrefix("/"))?.toString() ?: path }
            val stream = resolve((webcam?.get("stream_url") as? JsonPrimitive)?.contentOrNull)
            val snapshot = resolve((webcam?.get("snapshot_url") as? JsonPrimitive)?.contentOrNull)
            val service = (webcam?.get("service") as? JsonPrimitive)?.contentOrNull.orEmpty()
            return when {
                webcam == null -> CameraFeed.Mjpeg(resolve(DEFAULT_STREAM)!!)
                // WebRTC, HLS and the adaptive MJPEG streamer aren't plain MJPEG: the snapshots are.
                service == "mjpegstreamer" || service.isEmpty() -> stream?.let { CameraFeed.Mjpeg(it) } ?: snapshot?.let { CameraFeed.Snapshots(it) }
                else -> snapshot?.let { CameraFeed.Snapshots(it) } ?: stream?.let { CameraFeed.Mjpeg(it) }
            } ?: throw IOException("The printer's camera has no address")
        }
    }
}
