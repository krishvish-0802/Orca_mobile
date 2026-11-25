package com.orcamobile.core.printer.elegoo

import com.orcamobile.core.model.Axes
import com.orcamobile.core.model.FanKind
import com.orcamobile.core.model.FanState
import com.orcamobile.core.model.FilamentSlotState
import com.orcamobile.core.model.Heater
import com.orcamobile.core.model.HeaterKind
import com.orcamobile.core.model.MachineState
import com.orcamobile.core.model.PrintJob
import com.orcamobile.core.model.PrinterState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Elegoo's SDCP v3 messages, as the Centauri Carbon sends them, turned into the
 * app's [PrinterState]. Field names (misspellings included) and codes follow
 * the community SDCP reference for the Centauri Carbon and Elegoo's own
 * elegoo-link adapter, as the Home Assistant integration mirrors them.
 */
internal object SdcpStatus {

    /** A `sdcp/status` message's `Status` object over [previous]. */
    fun applyStatus(status: JsonObject, previous: PrinterState): PrinterState {
        val machine = (status["CurrentStatus"] as? JsonArray)?.mapNotNull { it.int() }
            ?: listOfNotNull(status["CurrentStatus"].int())
        val info = status["PrintInfo"] as? JsonObject
        val printStatus = info?.get("Status").int()
        val heaters = listOfNotNull(
            heater(HeaterKind.NOZZLE, status["TempOfNozzle"], status["TempTargetNozzle"], NOZZLE_MAX),
            heater(HeaterKind.BED, status["TempOfHotbed"], status["TempTargetHotbed"], BED_MAX),
            heater(HeaterKind.CHAMBER, status["TempOfBox"], status["TempTargetBox"], null),
        )
        val fanSpeeds = status["CurrentFanSpeed"] as? JsonObject
        val fans = listOfNotNull(
            (fanSpeeds?.get("ModelFan") ?: fanSpeeds?.get("ModeFan")).int()?.let { FanState(FanKind.PART, it) },
            fanSpeeds?.get("AuxiliaryFan").int()?.let { FanState(FanKind.AUXILIARY, it) },
            fanSpeeds?.get("BoxFan").int()?.let { FanState(FanKind.CHAMBER, it) },
        )
        val state = machineState(machine, printStatus)
        val errorNumber = info?.get("ErrorNumber").int() ?: 0
        return previous.copy(
            machine = state,
            job = info?.let { job(it, state) },
            heaters = heaters.ifEmpty { previous.heaters },
            fans = fans.ifEmpty { previous.fans },
            speedPercent = info?.get("PrintSpeedPct").int() ?: status["PrintSpeed"].int() ?: previous.speedPercent,
            lightOn = (status["LightStatus"] as? JsonObject)?.get("SecondLight").int()?.let { it == 1 } ?: previous.lightOn,
            position = position(status["CurrenCoord"] ?: status["CurrentCoord"]) ?: previous.position,
            message = printError(errorNumber) ?: if (state == MachineState.ERROR) "The printer reports an error. Check its screen." else null,
        )
    }

    /** A `sdcp/attributes` message's `Attributes` object over [previous]. */
    fun applyAttributes(attributes: JsonObject, previous: PrinterState): PrinterState = previous.copy(
        model = attributes["MachineName"].text() ?: attributes["Name"].text() ?: previous.model,
        firmware = attributes["FirmwareVersion"].text() ?: previous.firmware,
    )

    /** A Canvas (filament unit) status answer over [previous]. */
    fun applyCanvas(data: JsonObject, previous: PrinterState): PrinterState {
        val info = data["canvas_info"] as? JsonObject ?: data
        val activeUnit = info["active_canvas_id"].int()
        val activeTray = info["active_tray_id"].int()
        val slots = (info["canvas_list"] as? JsonArray).orEmpty().flatMap { unitElement ->
            val unit = unitElement as? JsonObject ?: return@flatMap emptyList()
            val unitId = unit["canvas_id"].int() ?: 0
            if ((unit["connected"].int() ?: 1) == 0) return@flatMap emptyList()
            (unit["tray_list"] as? JsonArray).orEmpty().mapNotNull { trayElement ->
                val tray = trayElement as? JsonObject ?: return@mapNotNull null
                val trayId = tray["tray_id"].int() ?: return@mapNotNull null
                FilamentSlotState(
                    index = unitId * SLOTS_PER_CANVAS + trayId,
                    unit = unitId,
                    loaded = tray["status"].int() == 1,
                    type = tray["filament_type"].text()?.takeUnless { it == "?" },
                    colour = tray["filament_color"].text()?.let { if (it.startsWith("#")) it else "#$it" },
                    name = tray["filament_name"].text()?.takeUnless { it.startsWith("—") },
                    active = unitId == activeUnit && trayId == activeTray,
                )
            }
        }
        return previous.copy(filamentSlots = slots)
    }

    /**
     * What the printer is doing: the machine's own status codes, and while it
     * works on a print, the print's (the Centauri's FDM table).
     */
    fun machineState(machine: List<Int>, print: Int?): MachineState = when {
        print != null && print in PRINT_ACTIVE -> printState(print)
        MACHINE_TRANSFERRING in machine -> MachineState.BUSY
        MACHINE_PRINTING in machine -> print?.let(::printState) ?: MachineState.PRINTING
        machine.any { it in MACHINE_BUSY } -> MachineState.BUSY
        print == PRINT_COMPLETE -> MachineState.FINISHED
        print == PRINT_STOPPED -> MachineState.CANCELLED
        print == PRINT_ERROR -> MachineState.ERROR
        machine.isEmpty() && print == null -> MachineState.UNKNOWN
        else -> MachineState.IDLE
    }

    private fun printState(code: Int): MachineState = when (code) {
        0 -> MachineState.IDLE
        3, 12, 13 -> MachineState.PRINTING
        5 -> MachineState.PAUSING
        6, 26 -> MachineState.PAUSED
        7 -> MachineState.STOPPING
        8 -> MachineState.CANCELLED
        9 -> MachineState.FINISHED
        14, 25 -> MachineState.ERROR
        24 -> MachineState.BUSY
        else -> MachineState.PREPARING
    }

    private fun job(info: JsonObject, state: MachineState): PrintJob? {
        val name = info["Filename"].text()?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return null
        if (state == MachineState.IDLE || state == MachineState.UNKNOWN) return null
        val layer = info["CurrentLayer"].int()
        val layers = info["TotalLayer"].int()
        // The Centauri counts seconds in its ticks.
        val elapsed = info["CurrentTicks"].long()
        val total = info["TotalTicks"].long()
        val progress = info["Progress"].double()?.div(100.0)
            ?: if (layer != null && layers != null && layers > 0) layer.toDouble() / layers else null
        return PrintJob(
            fileName = name,
            progress = progress?.coerceIn(0.0, 1.0) ?: if (state == MachineState.FINISHED) 1.0 else null,
            currentLayer = layer,
            totalLayers = layers,
            elapsedSeconds = elapsed,
            remainingSeconds = if (elapsed != null && total != null) (total - elapsed).coerceAtLeast(0) else null,
        )
    }

    private fun heater(kind: HeaterKind, current: JsonElement?, target: JsonElement?, max: Double?): Heater? {
        val now = current.double() ?: return null
        return Heater(kind, now, target.double() ?: 0.0, max)
    }

    private fun position(value: JsonElement?): Axes? {
        val parts = value.text()?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
        return if (parts.size >= 3) Axes(parts[0], parts[1], parts[2]) else null
    }

    /** `PrintInfo.ErrorNumber`. */
    fun printError(code: Int): String? = when (code) {
        0 -> null
        1 -> "The file failed its check (MD5). Send it again."
        2 -> "The printer couldn't read the file."
        3 -> "The file's resolution doesn't match the printer."
        4 -> "The printer doesn't know the file's format."
        5 -> "The file was sliced for another printer model."
        else -> "The printer reports error $code."
    }

    /** A finished task's `ErrorStatusReason`, in plain words. */
    fun taskError(code: Int): String? = when (code) {
        0 -> null
        1 -> "Temperature error"
        3 -> "Filament ran out"
        6 -> "Filament jammed"
        7 -> "Bed levelling failed"
        12 -> "The USB drive was removed"
        13 -> "X axis homing failed"
        14 -> "Z axis homing failed"
        17 -> "Homing failed"
        18 -> "The print came off the bed"
        19 -> "Printer error"
        20 -> "A motor didn't move as expected"
        23 -> "Y axis homing failed"
        24 -> "File error"
        25 -> "Camera error"
        26 -> "Network error"
        27 -> "Couldn't reach the server"
        28 -> "The app disconnected"
        33 -> "The nozzle's temperature sensor is disconnected"
        34 -> "The bed's temperature sensor is disconnected"
        else -> "Error $code"
    }

    const val SLOTS_PER_CANVAS = 4
    private const val NOZZLE_MAX = 320.0
    private const val BED_MAX = 110.0
    private const val MACHINE_PRINTING = 1
    private const val MACHINE_TRANSFERRING = 2
    private val MACHINE_BUSY = setOf(3, 4, 5, 6, 9, 10, 11)
    private const val PRINT_STOPPED = 8
    private const val PRINT_COMPLETE = 9
    private const val PRINT_ERROR = 14

    /** Print codes that mean a print is under way (not idle, finished or stopped). */
    private val PRINT_ACTIVE = setOf(1, 3, 5, 6, 7, 10, 11, 12, 13, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26)

    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonElement?.double(): Double? = (this as? JsonPrimitive)?.doubleOrNull
    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
}
