package com.orcamobile.core.printer.elegoo

import com.orcamobile.core.model.Axes
import com.orcamobile.core.model.FanKind
import com.orcamobile.core.model.FanState
import com.orcamobile.core.model.Heater
import com.orcamobile.core.model.HeaterKind
import com.orcamobile.core.model.MachineState
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.model.PrinterState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class SdcpStatusTest {

    private fun status(json: String) = Json.parseToJsonElement(json).jsonObject

    /** A Centauri Carbon halfway through a print, as it reports one. */
    private val printing = status(
        """{"CurrentStatus":[1],"TempOfNozzle":219.6,"TempTargetNozzle":220,"TempOfHotbed":59.8,"TempTargetHotbed":60,
           "TempOfBox":31.2,"TempTargetBox":0,"CurrenCoord":"120.50,98.00,12.40",
           "CurrentFanSpeed":{"ModelFan":100,"AuxiliaryFan":40,"BoxFan":20},"LightStatus":{"SecondLight":1},
           "PrintInfo":{"Status":13,"CurrentLayer":62,"TotalLayer":124,"CurrentTicks":1800,"TotalTicks":3600,
           "Filename":"/local/cube.gcode","ErrorNumber":0,"TaskId":"t1","PrintSpeedPct":100,"Progress":50}}""",
    )

    @Test
    fun `a print in progress reads with its job, temperatures, fans and light`() {
        val state = SdcpStatus.applyStatus(printing, PrinterState())
        assertEquals(MachineState.PRINTING, state.machine)
        val job = state.job!!
        assertEquals("cube.gcode", job.fileName)
        assertEquals(0.5, job.progress)
        assertEquals(62, job.currentLayer)
        assertEquals(124, job.totalLayers)
        assertEquals(1800L, job.remainingSeconds)
        assertEquals(
            listOf(Heater(HeaterKind.NOZZLE, 219.6, 220.0, 320.0), Heater(HeaterKind.BED, 59.8, 60.0, 110.0), Heater(HeaterKind.CHAMBER, 31.2, 0.0, null)),
            state.heaters,
        )
        assertEquals(listOf(FanState(FanKind.PART, 100), FanState(FanKind.AUXILIARY, 40), FanState(FanKind.CHAMBER, 20)), state.fans)
        assertEquals(true, state.lightOn)
        assertEquals(100, state.speedPercent)
        assertEquals(Axes(120.5, 98.0, 12.4), state.position)
        assertNull(state.message)
    }

    @Test
    fun `the Centauri's print codes read as what the printer is doing`() {
        assertEquals(MachineState.IDLE, SdcpStatus.machineState(listOf(0), 0))
        assertEquals(MachineState.PAUSED, SdcpStatus.machineState(listOf(1), 6))
        assertEquals(MachineState.PREPARING, SdcpStatus.machineState(listOf(1), 16))
        assertEquals(MachineState.FINISHED, SdcpStatus.machineState(listOf(0), 9))
        assertEquals(MachineState.CANCELLED, SdcpStatus.machineState(listOf(0), 8))
        assertEquals(MachineState.BUSY, SdcpStatus.machineState(listOf(2), 0))
        assertEquals(MachineState.UNKNOWN, SdcpStatus.machineState(emptyList(), null))
    }

    @Test
    fun `an idle printer has no job, and a finished one keeps it at full progress`() {
        assertNull(SdcpStatus.applyStatus(status("""{"CurrentStatus":[0],"PrintInfo":{"Status":0,"Filename":"/local/old.gcode"}}"""), PrinterState()).job)
        val done = SdcpStatus.applyStatus(status("""{"CurrentStatus":[0],"PrintInfo":{"Status":9,"Filename":"/local/cube.gcode"}}"""), PrinterState())
        assertEquals(MachineState.FINISHED, done.machine)
        assertEquals(1.0, done.job?.progress)
    }

    @Test
    fun `the Canvas reports its loaded slots and the one in use`() {
        val canvas = status(
            """{"Ack":0,"canvas_info":{"active_canvas_id":0,"active_tray_id":1,"canvas_list":[{"canvas_id":0,"connected":1,"tray_list":[
               {"tray_id":0,"filament_type":"PLA","filament_color":"FF0000","filament_name":"Red","status":1},
               {"tray_id":1,"filament_type":"PETG","filament_color":"#0000FF","status":1},
               {"tray_id":2,"filament_type":"?","status":0}]}]}}""",
        )
        val slots = SdcpStatus.applyCanvas(canvas, PrinterState()).filamentSlots
        assertEquals(listOf(0, 1, 2), slots.map { it.index })
        assertEquals(listOf("#FF0000", "#0000FF", null), slots.map { it.colour })
        assertEquals(listOf(false, true, false), slots.map { it.active })
        assertEquals(listOf(true, true, false), slots.map { it.loaded })
        assertNull(slots[2].type)
    }

    @Test
    fun `a discovery answer names the printer and its address`() {
        val found = SdcpDiscovery.parse(
            """{"Id":"x","Data":{"Name":"Workshop","MachineName":"Centauri Carbon","BrandName":"ELEGOO","MainboardIP":"192.168.1.40","MainboardID":"abc123","ProtocolVersion":"V3.0.0"}}""",
            "192.168.1.40",
        )!!
        assertEquals("Workshop", found.name)
        assertEquals("ELEGOO Centauri Carbon", found.model)
        assertEquals(PrintHostType.ELEGOO_LINK, found.host.type)
        assertEquals("192.168.1.40", found.host.baseUrl)
        assertEquals("abc123", found.uniqueId)
        assertNull(SdcpDiscovery.parse("not json", "1.2.3.4"))
    }
}
