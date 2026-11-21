package com.orcamobile.core.printer

import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.printer.elegoo.ElegooLinkClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// runBlocking, not runTest: the WebSocket answers on real threads, and runTest's
// virtual clock would expire the session's timeouts while it waits for them.
class ElegooLinkClientTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client() = ElegooLinkClient(
        PrintHostConfig(PrintHostType.ELEGOO_LINK, server.hostName + ":" + server.port),
        http,
        sdcpPort = server.port,
        sdcpSettleMillis = 0,
    )

    private fun gcode(bytes: Int) = temp.newFile("cube.gcode").apply { writeBytes(Random(7).nextBytes(bytes)) }

    private val uploadOk = MockResponse().setBody("""{"code":"000000","messages":null,"data":{},"success":true}""")

    @Test
    fun `the connection test looks for an Elegoo page`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html><title>ELEGOO Centauri Carbon</title></html>"))
        assertEquals("Elegoo printer (ElegooLink)", client().testConnection().getOrThrow().description)
        assertEquals("/", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("<html>Fluidd</html>"))
        assertTrue(client().testConnection().isFailure)
    }

    @Test
    fun `uploads in 1 MiB parts with the file's MD5, one id and the offsets`() = runBlocking {
        val file = gcode(2 * ElegooLinkClient.PART_BYTES + 1000)
        repeat(3) { server.enqueue(uploadOk) }
        var progress = 0L to 0L

        client().upload(file.path, "cube.gcode", startPrint = false) { sent, total -> progress = sent to total }.getOrThrow()

        val md5 = ElegooLinkClient.md5Hex(file)
        assertEquals(md5.uppercase(), md5)
        val parts = List(3) { server.takeRequest() }
        val bodies = parts.map { it.body.readUtf8() }
        assertEquals(1, bodies.map { field(it, "Uuid") }.toSet().size)
        parts.forEachIndexed { i, part ->
            assertEquals("/uploadFile/upload", part.path)
            val body = bodies[i]
            assertEquals("1", field(body, "Check"))
            assertEquals(md5, field(body, "S-File-MD5"))
            assertEquals((i.toLong() * ElegooLinkClient.PART_BYTES).toString(), field(body, "Offset"))
            assertEquals(file.length().toString(), field(body, "TotalSize"))
            assertTrue("filename=\"cube.gcode\"" in body)
        }
        assertEquals(file.length() to file.length(), progress)
    }

    @Test
    fun `reports what the printer says when it refuses a part`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":"111111","messages":[{"field":"File","message":"No space"}]}"""))
        val error = client().upload(gcode(100).path, "cube.gcode", startPrint = false).exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("No space"), error?.message)
    }

    @Test
    fun `starts the print once the printer has checked the file`() = runBlocking {
        val printer = FakeSdcpPrinter(checkingRounds = 2, ack = 0)
        server.enqueue(uploadOk)
        server.enqueue(MockResponse().withWebSocketUpgrade(printer))

        client().upload(gcode(100).path, "cube.gcode", startPrint = true).getOrThrow()

        assertEquals(3, printer.statusRequests)
        val start = printer.startRequests.single()
        assertEquals("/local/cube.gcode", start["Filename"]?.jsonPrimitive?.content)
        assertEquals(0, start["StartLayer"]?.jsonPrimitive?.int)
    }

    @Test
    fun `explains a refused start`() = runBlocking {
        server.enqueue(uploadOk)
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeSdcpPrinter(checkingRounds = 0, ack = 1)))
        val error = client().upload(gcode(100).path, "cube.gcode", startPrint = true).exceptionOrNull()
        assertTrue(error?.message.orEmpty().startsWith("The printer is busy"), error?.message)
    }

    /** A multipart form field's value. */
    private fun field(body: String, name: String): String =
        Regex("name=\"${Regex.escape(name)}\"\\r\\n(?:[^\\r\\n]+\\r\\n)*\\r\\n([^\\r\\n]*)\\r\\n").find(body)?.groupValues?.get(1)
            ?: error("No field $name")

    /** The printer's SDCP side: reports "checking file" (8) for [checkingRounds] status requests, then idle. */
    private class FakeSdcpPrinter(private val checkingRounds: Int, private val ack: Int) : WebSocketListener() {
        @Volatile var statusRequests = 0
        val startRequests = CopyOnWriteArrayList<JsonObject>()

        override fun onMessage(webSocket: WebSocket, text: String) {
            val data = Json.parseToJsonElement(text).jsonObject.getValue("Data").jsonObject
            when (data.getValue("Cmd").jsonPrimitive.int) {
                0 -> {
                    statusRequests++
                    val status = if (statusRequests <= checkingRounds) 8 else 0
                    webSocket.send("""{"Status":{"CurrentStatus":[$status]},"Topic":"sdcp/status/X"}""")
                }
                128 -> {
                    startRequests += data.getValue("Data").jsonObject
                    webSocket.send("""{"Id":"x","Data":{"Cmd":128,"Data":{"Ack":$ack},"RequestID":"r"},"Topic":"sdcp/response/X"}""")
                }
            }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) = Unit

        // Finish the close handshake the client starts, so the server can shut down.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }
    }
}
