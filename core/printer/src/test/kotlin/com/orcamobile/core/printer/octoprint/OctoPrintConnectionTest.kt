package com.orcamobile.core.printer.octoprint

import com.orcamobile.core.model.Axis
import com.orcamobile.core.model.CameraFeed
import com.orcamobile.core.model.HeaterKind
import com.orcamobile.core.model.MachineState
import com.orcamobile.core.model.PrinterLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

class OctoPrintConnectionTest {

    private lateinit var server: MockWebServer
    private lateinit var connection: OctoPrintConnection
    private val posts = CopyOnWriteArrayList<Pair<String, String>>()

    @Volatile
    private var connected = true

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (request.method == "POST") {
                        posts += path to request.body.readUtf8()
                        return MockResponse().setResponseCode(204)
                    }
                    return when {
                        path == "/api/version" -> MockResponse().setBody("""{"api":"0.1","server":"1.10.2","text":"OctoPrint 1.10.2"}""")
                        path == "/api/printer" && !connected -> MockResponse().setResponseCode(409).setBody("Printer is not operational")
                        path == "/api/printer" -> MockResponse().setBody(
                            """{"temperature":{"tool0":{"actual":214.8,"target":215.0},"bed":{"actual":60.1,"target":60.0}},
                               "state":{"text":"Printing","flags":{"operational":true,"printing":true,"paused":false,"error":false,"ready":false}}}""",
                        )
                        path == "/api/job" -> MockResponse().setBody(
                            """{"job":{"file":{"name":"benchy.gcode","path":"benchy.gcode"}},"progress":{"completion":42.5,"printTime":1200,"printTimeLeft":1600},"state":"Printing"}""",
                        )
                        path.startsWith("/api/files/local") -> MockResponse().setBody(
                            """{"files":[{"name":"benchy.gcode","path":"benchy.gcode","type":"machinecode","size":10},
                               {"name":"parts","path":"parts","type":"folder","children":[{"name":"clip.gcode","path":"parts/clip.gcode","type":"machinecode"}]}]}""",
                        )
                        path == "/api/settings" -> MockResponse().setBody("""{"plugins":{"classicwebcam":{"stream":"/webcam/?action=stream","snapshot":"http://127.0.0.1:8080/?action=snapshot"}}}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        connection = OctoPrintConnection(server.url("/"), "key", OkHttpClient(), Dispatchers.IO, intervalMillis = 200).also { it.start() }
    }

    @After
    fun tearDown() {
        connection.close()
        server.shutdown()
    }

    @Test
    fun `the printer and job answers read as the print in progress`() = runBlocking {
        val state = withTimeout(5_000) { connection.state.first { it.link == PrinterLink.Online } }
        assertEquals(MachineState.PRINTING, state.machine)
        assertEquals("benchy.gcode", state.job?.fileName)
        assertEquals(0.425, state.job?.progress)
        assertEquals(1600L, state.job?.remainingSeconds)
        assertEquals(listOf(HeaterKind.NOZZLE, HeaterKind.BED), state.heaters.map { it.kind })
        assertEquals("OctoPrint 1.10.2", state.firmware)

        connected = false
        val unplugged = withTimeout(5_000) { connection.state.first { it.message == OctoPrintConnection.NOT_CONNECTED } }
        assertEquals(PrinterLink.Online, unplugged.link)
    }

    @Test
    fun `controls go out as OctoPrint's job, tool and print head commands`() = runBlocking {
        withTimeout(5_000) { connection.state.first { it.link == PrinterLink.Online } }
        assertTrue(connection.jobs.resume().isSuccess)
        assertTrue(connection.temperatures.setTarget(HeaterKind.NOZZLE, 200).isSuccess)
        assertTrue(connection.motion.jog(Axis.Z, 0.1).isSuccess)
        assertTrue(connection.console.send("M115\nM114").isSuccess)
        val sent = posts.map { (path, body) -> path to Json.parseToJsonElement(body).jsonObject.toString() }
        assertEquals("/api/job" to """{"command":"pause","action":"resume"}""", sent[0])
        assertEquals("/api/printer/tool" to """{"command":"target","targets":{"tool0":200}}""", sent[1])
        assertEquals("/api/printer/printhead" to """{"command":"jog","z":0.1}""", sent[2])
        assertEquals("/api/printer/command" to """{"commands":["M115","M114"]}""", sent[3])
    }

    @Test
    fun `files in folders are listed, and the camera comes from the webcam settings`() = runBlocking {
        assertEquals(listOf("benchy.gcode", "parts/clip.gcode"), connection.files.list().getOrThrow().map { it.path })
        assertEquals(CameraFeed.Mjpeg(server.url("/webcam/?action=stream").toString()), connection.camera.open().getOrThrow())
    }
}
