package com.orcamobile.core.printer.moonraker

import com.orcamobile.core.model.Axes
import com.orcamobile.core.model.FanKind
import com.orcamobile.core.model.FanState
import com.orcamobile.core.model.Heater
import com.orcamobile.core.model.HeaterKind
import com.orcamobile.core.model.MachineState
import com.orcamobile.core.model.PrintJob
import com.orcamobile.core.model.PrintRecord
import com.orcamobile.core.model.PrinterFile
import com.orcamobile.core.model.PrinterState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Klipper's printer objects, as Moonraker's `printer/objects/query` returns
 * them, read into a [PrinterState]. The objects asked for are [QUERY]; the
 * names are Klipper's (docs/Status_Reference.md).
 */
internal object MoonrakerStatus {

    /** The chamber heater or sensor, by the names Klipper configs use most. */
    private const val CHAMBER_HEATER = "heater_generic chamber"
    private const val CHAMBER_SENSOR = "temperature_sensor chamber"

    /** The query string for `printer/objects/query`. */
    val QUERY = listOf(
        "webhooks", "print_stats", "virtual_sdcard", "display_status", "extruder", "heater_bed",
        CHAMBER_HEATER, CHAMBER_SENSOR, "fan", "gcode_move", "toolhead",
    ).joinToString("&") { it.replace(" ", "%20") }

    fun apply(status: JsonObject, previous: PrinterState): PrinterState {
        val webhooks = status.obj("webhooks")
        val stats = status.obj("print_stats")
        val klippy = webhooks?.string("state")
        val printState = stats?.string("state")
        val machine = when (klippy) {
            "startup" -> MachineState.BUSY
            "shutdown", "error" -> MachineState.ERROR
            else -> machineState(printState)
        }
        val message = when {
            klippy == "shutdown" || klippy == "error" -> webhooks?.string("state_message")?.trim()
            printState == "error" -> stats?.string("message")?.trim()
            else -> null
        }?.ifEmpty { null }

        val heaters = buildList {
            status.heater("extruder")?.let { add(Heater(HeaterKind.NOZZLE, it.first, it.second)) }
            status.heater("heater_bed")?.let { add(Heater(HeaterKind.BED, it.first, it.second)) }
            (status.heater(CHAMBER_HEATER) ?: status.heater(CHAMBER_SENSOR))?.let { add(Heater(HeaterKind.CHAMBER, it.first, it.second)) }
        }
        val fanSpeed = status.obj("fan")?.number("speed")
        val position = (status.obj("toolhead")?.get("position") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            ?.takeIf { it.size >= 3 }
            ?.let { Axes(it[0], it[1], it[2]) }

        return previous.copy(
            machine = machine,
            job = stats?.let { job(it, status, machine) },
            heaters = heaters.ifEmpty { previous.heaters },
            fans = fanSpeed?.let { listOf(FanState(FanKind.PART, (it * 100).roundToInt())) } ?: previous.fans,
            speedPercent = status.obj("gcode_move")?.number("speed_factor")?.let { (it * 100).roundToInt() } ?: previous.speedPercent,
            position = position ?: previous.position,
            message = message,
        )
    }

    fun machineState(printState: String?): MachineState = when (printState) {
        "standby" -> MachineState.IDLE
        "printing" -> MachineState.PRINTING
        "paused" -> MachineState.PAUSED
        "complete" -> MachineState.FINISHED
        "cancelled" -> MachineState.CANCELLED
        "error" -> MachineState.ERROR
        else -> MachineState.UNKNOWN
    }

    private fun job(stats: JsonObject, status: JsonObject, machine: MachineState): PrintJob? {
        val file = stats.string("filename")?.takeIf { it.isNotEmpty() } ?: return null
        if (machine == MachineState.IDLE) return null
        val progress = (status.obj("virtual_sdcard")?.number("progress") ?: status.obj("display_status")?.number("progress"))
            ?.coerceIn(0.0, 1.0)
            ?.let { if (machine == MachineState.FINISHED) 1.0 else it }
        val printed = stats.number("print_duration")
        val info = stats.obj("info")
        // As Mainsail's "file" estimate: the time so far, scaled by what's left.
        val remaining = if (printed != null && progress != null && progress in 0.01..0.999) (printed / progress - printed).roundToLong() else null
        return PrintJob(
            fileName = file.substringAfterLast('/'),
            progress = progress,
            currentLayer = info?.number("current_layer")?.toInt(),
            totalLayers = info?.number("total_layer")?.toInt(),
            elapsedSeconds = printed?.roundToLong(),
            remainingSeconds = remaining,
        )
    }

    /** `server/files/list` (root gcodes): every file, with its path under the root. */
    fun files(result: JsonElement): List<PrinterFile> = (result as? JsonArray).orEmpty().mapNotNull { entry ->
        val file = entry as? JsonObject ?: return@mapNotNull null
        val path = file.string("path") ?: file.string("filename") ?: return@mapNotNull null
        PrinterFile(path, path.substringAfterLast('/'), file.number("size")?.toLong())
    }

    /** `server/history/list`: the most recent prints first. */
    fun history(result: JsonElement): List<PrintRecord> = ((result as? JsonObject)?.get("jobs") as? JsonArray).orEmpty().mapNotNull { entry ->
        val job = entry as? JsonObject ?: return@mapNotNull null
        val id = job.string("job_id") ?: return@mapNotNull null
        val status = job.string("status")
        PrintRecord(
            id = id,
            fileName = job.string("filename")?.substringAfterLast('/').orEmpty(),
            startedEpochSeconds = job.number("start_time")?.toLong(),
            endedEpochSeconds = job.number("end_time")?.toLong(),
            outcome = when (status) {
                "completed" -> MachineState.FINISHED
                "cancelled" -> MachineState.CANCELLED
                "in_progress" -> MachineState.PRINTING
                else -> MachineState.ERROR
            },
            reason = status?.takeIf { it != "completed" && it != "cancelled" && it != "in_progress" }?.replace('_', ' '),
        )
    }

    private fun JsonObject.heater(name: String): Pair<Double, Double>? {
        val heater = obj(name) ?: return null
        val current = heater.number("temperature") ?: return null
        return current to (heater.number("target") ?: 0.0)
    }

    private fun JsonObject.obj(name: String) = get(name) as? JsonObject

    private fun JsonObject.string(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(name: String) = (get(name) as? JsonPrimitive)?.doubleOrNull
}
