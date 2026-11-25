package com.orcamobile.core.printer.moonraker

import com.orcamobile.core.model.Axis
import com.orcamobile.core.model.CameraFeed
import com.orcamobile.core.model.HeaterKind
import com.orcamobile.core.model.MachineState
import com.orcamobile.core.model.PrinterFile
import com.orcamobile.core.model.PrinterLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

class MoonrakerConnectionTest {

    private lateinit var server: MockWebServer
    private lateinit var connection: MoonrakerConnection
    private val requests = CopyOnWriteArrayList<String>()

    /** A Voron halfway through a print. */
    private val status = """{"result":{"eventtime":1,"status":{
        "webhooks":{"state":"ready","state_message":"Printer is ready"},
        "print_stats":{"state":"printing","filename":"parts/bracket.gcode","print_duration":600.0,"message":"","info":{"current_layer":20,"total_layer":80}},
        "virtual_sdcard":{"progress":0.25,"is_active":true},
        "extruder":{"temperature":239.8,"target":240.0},"heater_bed":{"temperature":99.5,"target":100.0},
        "heater_generic chamber":{"temperature":45.0,"target":50.0},
        "fan":{"speed":0.5},"gcode_move":{"speed_factor":1.1},"toolhead":{"position":[100.0,120.5,4.2,1500.0]}}}}"""

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    requests += "${request.method} $path"
                    val body = when {
                        path.startsWith("/server/info") -> """{"result":{"klippy_state":"ready"}}"""
                        path.startsWith("/printer/info") -> """{"result":{"hostname":"voron","software_version":"v0.12.0"}}"""
                        path.startsWith("/printer/objects/query") -> status
                        path.startsWith("/server/files/list") -> """{"result":[{"path":"parts/bracket.gcode","size":2048,"modified":1},{"path":"cube.gcode","size":1024}]}"""
                        path.startsWith("/server/history/list") -> """{"result":{"count":2,"jobs":[
                            {"job_id":"0002","filename":"cube.gcode","status":"completed","start_time":100.0,"end_time":700.0},
                            {"job_id":"0001","filename":"parts/bracket.gcode","status":"klippy_shutdown","start_time":10.0,"end_time":50.0}]}}"""
                        path.startsWith("/server/webcams/list") -> """{"result":{"webcams":[{"name":"cam","enabled":true,"service":"mjpegstreamer","stream_url":"/webcam/?action=stream","snapshot_url":"/webcam/?action=snapshot"}]}}"""
                        else -> """{"result":"ok"}"""
                    }
                    return MockResponse().setBody(body)
                }
            }
            start()
        }
        val api = MoonrakerApi(listOf(server.url("/")), null, OkHttpClient())
        connection = MoonrakerConnection(api, Dispatchers.IO, intervalMillis = 200).also { it.start() }
    }

    @After
    fun tearDown() {
        connection.close()
        server.shutdown()
    }

    private fun online() = runBlocking { withTimeout(5_000) { connection.state.first { it.link == PrinterLink.Online } } }

    @Test
    fun `Klipper's objects read as the print, heaters, fan, speed and toolhead`() {
        val state = online()
        assertEquals(MachineState.PRINTING, state.machine)
        val job = state.job!!
        assertEquals("bracket.gcode", job.fileName)
        assertEquals(0.25, job.progress)
        assertEquals(20, job.currentLayer)
        assertEquals(80, job.totalLayers)
        assertEquals(600L, job.elapsedSeconds)
        assertEquals(1800L, job.remainingSeconds)
        assertEquals(listOf(HeaterKind.NOZZLE, HeaterKind.BED, HeaterKind.CHAMBER), state.heaters.map { it.kind })
        assertEquals(240.0, state.heaters[0].target)
        assertEquals(50, state.fans.single().percent)
        assertEquals(110, state.speedPercent)
        assertEquals(4.2, state.position?.z)
        assertEquals("voron", state.model)
        assertEquals("v0.12.0", state.firmware)
        assertNull(state.message)
        assertTrue(HeaterKind.CHAMBER in connection.temperatures.heaters)
    }

    @Test
    fun `controls go out as Moonraker's print endpoints and Klipper G-code`() = runBlocking {
        online()
        assertTrue(connection.jobs.pause().isSuccess)
        assertTrue(connection.temperatures.setTarget(HeaterKind.BED, 60).isSuccess)
        assertTrue(connection.fans.setFan(com.orcamobile.core.model.FanKind.PART, 100).isSuccess)
        assertTrue(connection.motion.jog(Axis.X, -10.0).isSuccess)
        assertTrue(connection.files.print(PrinterFile("parts/bracket.gcode", "bracket.gcode")).isSuccess)
        val posts = requests.filter { it.startsWith("POST") }
        assertEquals("POST /printer/print/pause", posts[0])
        assertEquals("POST /printer/gcode/script?script=SET_HEATER_TEMPERATURE%20HEATER%3Dheater_bed%20TARGET%3D60", posts[1])
        assertEquals("POST /printer/gcode/script?script=M106%20S255", posts[2])
        assertTrue(posts[3].contains("G1%20X-10.0%20F6000"), posts[3])
        assertEquals("POST /printer/print/start?filename=parts%2Fbracket.gcode", posts[4])
    }

    @Test
    fun `files, history and the camera come from Moonraker's server endpoints`() = runBlocking {
        online()
        assertEquals(listOf("cube.gcode", "parts/bracket.gcode"), connection.files.list().getOrThrow().map { it.path })
        val history = connection.history.recent().getOrThrow()
        assertEquals(listOf(MachineState.FINISHED, MachineState.ERROR), history.map { it.outcome })
        assertEquals("klippy shutdown", history[1].reason)
        assertEquals(CameraFeed.Mjpeg(server.url("/webcam/?action=stream").toString()), connection.camera.open().getOrThrow())
    }

    @Test
    fun `a shut-down Klipper reads as an error with its message`() {
        val shutdown = Json.parseToJsonElement(
            """{"webhooks":{"state":"shutdown","state_message":"MCU 'mcu' shutdown: Timer too close\n"},"print_stats":{"state":"error","filename":"a.gcode"}}""",
        ).jsonObject
        val state = MoonrakerStatus.apply(shutdown, com.orcamobile.core.model.PrinterState())
        assertEquals(MachineState.ERROR, state.machine)
        assertEquals("MCU 'mcu' shutdown: Timer too close", state.message)
    }

    @Test
    fun `the camera's paths are served by the web server on port 80, not Moonraker's port`() {
        val webcam = Json.parseToJsonElement("""{"service":"webrtc-camerastreamer","stream_url":"/webcam/webrtc","snapshot_url":"/webcam/?action=snapshot"}""").jsonObject
        assertEquals(
            CameraFeed.Snapshots("http://voron.local/webcam/?action=snapshot"),
            MoonrakerConnection.cameraFeed(webcam, "http://voron.local:7125/".toHttpUrl()),
        )
        assertEquals(CameraFeed.Mjpeg("http://voron.local/webcam/?action=stream"), MoonrakerConnection.cameraFeed(null, "http://voron.local:7125/".toHttpUrl()))
    }
}
