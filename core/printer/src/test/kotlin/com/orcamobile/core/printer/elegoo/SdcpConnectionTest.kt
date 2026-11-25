package com.orcamobile.core.printer.elegoo

import com.orcamobile.core.model.CameraFeed
import com.orcamobile.core.model.HeaterKind
import com.orcamobile.core.model.MachineState
import com.orcamobile.core.model.PrinterLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

// runBlocking: the WebSocket answers on real threads.
class SdcpConnectionTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private lateinit var connection: SdcpConnection

    /** What the app sent, and how the fake printer answers each command (by number). */
    private val received = CopyOnWriteArrayList<JsonObject>()
    private val answers = mutableMapOf<Int, String>()

    /** A Centauri Carbon: answers commands, and pushes its status when asked. */
    private inner class FakePrinter : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text == "ping") {
                webSocket.send("pong")
                return
            }
            val message = Json.parseToJsonElement(text).jsonObject
            received += message
            val data = message["Data"]!!.jsonObject
            val cmd = data["Cmd"]!!.jsonPrimitive.int
            val id = data["RequestID"]!!.jsonPrimitive.content
            if (cmd == SdcpConnection.CMD_STATUS) {
                webSocket.send(
                    """{"Status":{"CurrentStatus":[1],"TempOfNozzle":210,"TempTargetNozzle":220,"PrintInfo":{"Status":13,"Filename":"/local/cube.gcode","Progress":25}},
                       "MainboardID":"board1","Topic":"sdcp/status/board1"}""",
                )
            }
            val reply = answers[cmd] ?: """{"Ack":0}"""
            webSocket.send("""{"Id":"","Data":{"Cmd":$cmd,"Data":$reply,"RequestID":"$id","MainboardID":"board1"},"Topic":"sdcp/response/board1"}""")
        }

        override fun onOpen(webSocket: WebSocket, response: Response) = Unit

        // Finish the close handshake, as the printer does, so the server can shut down.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(FakePrinter()))
            start()
        }
        connection = SdcpConnection(server.hostName, http, Dispatchers.IO, port = server.port, refreshMillis = 200, heartbeatMillis = 1_000)
    }

    @After
    fun tearDown() {
        connection.close()
        server.shutdown()
    }

    private fun sent(cmd: Int) = received.last { it["Data"]!!.jsonObject["Cmd"]!!.jsonPrimitive.int == cmd }["Data"]!!.jsonObject["Data"]!!.jsonObject

    @Test
    fun `the printer's status comes in as it is pushed`() = runBlocking {
        val state = withTimeout(5_000) { connection.state.first { it.machine == MachineState.PRINTING } }
        assertEquals(PrinterLink.Online, state.link)
        assertEquals("cube.gcode", state.job?.fileName)
        assertEquals(0.25, state.job?.progress)
        assertEquals(210.0, state.heaters.first { it.kind == HeaterKind.NOZZLE }.current)
    }

    @Test
    fun `commands go out with the printer's id and come back answered`() = runBlocking {
        withTimeout(5_000) { connection.state.first { it.link == PrinterLink.Online && it.machine != MachineState.UNKNOWN } }
        assertTrue(connection.jobs.pause().isSuccess)
        assertTrue(connection.temperatures.setTarget(HeaterKind.BED, 65).isSuccess)
        assertEquals(65, sent(SdcpConnection.CMD_CONTROL)["TempTargetHotbed"]!!.jsonPrimitive.int)
        assertEquals("board1", received.last()["Data"]!!.jsonObject["MainboardID"]!!.jsonPrimitive.content)
        assertTrue(connection.light.setLight(true).isSuccess)
        assertEquals(1, sent(SdcpConnection.CMD_CONTROL)["LightStatus"]!!.jsonObject["SecondLight"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the camera's stream address comes from the printer, and a refusal says why`() = runBlocking {
        withTimeout(5_000) { connection.state.first { it.link == PrinterLink.Online } }
        answers[SdcpConnection.CMD_VIDEO] = """{"Ack":0,"VideoUrl":"192.168.1.40:3031/video"}"""
        assertEquals(CameraFeed.Mjpeg("http://192.168.1.40:3031/video"), connection.camera.open().getOrThrow())
        assertEquals(1, sent(SdcpConnection.CMD_VIDEO)["Enable"]!!.jsonPrimitive.int)

        answers[SdcpConnection.CMD_VIDEO] = """{"Ack":1}"""
        val refused = connection.camera.open().exceptionOrNull()
        assertTrue(refused?.message.orEmpty().contains("Too many people"), refused?.message)
    }

    @Test
    fun `the printer's files are listed with their paths`() = runBlocking {
        withTimeout(5_000) { connection.state.first { it.link == PrinterLink.Online } }
        answers[SdcpConnection.CMD_FILES] =
            """{"Ack":0,"FileList":[{"name":"/local/cube.gcode","usedSize":1048576,"type":1},{"name":"/local/parts","type":0}]}"""
        val files = connection.files.list().getOrThrow()
        assertEquals(listOf("/local/cube.gcode", "/local/parts"), files.map { it.path })
        assertEquals(listOf(false, true), files.map { it.isFolder })
        assertEquals("cube.gcode", files[0].name)
    }
}
