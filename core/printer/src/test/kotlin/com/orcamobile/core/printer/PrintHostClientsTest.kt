package com.orcamobile.core.printer

import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.printer.http.PrintHostHttpException
import com.orcamobile.core.printer.moonraker.MoonrakerClient
import com.orcamobile.core.printer.moonraker.moonrakerCandidates
import com.orcamobile.core.printer.octoprint.OctoPrintClient
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PrintHostClientsTest {

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

    private fun config(type: PrintHostType, apiKey: String? = null) =
        // A bare host:port, as users type it, exercises URL normalization too.
        PrintHostConfig(type, "${server.hostName}:${server.port}", apiKey)

    private fun gcode(): String = temp.newFile("cube.gcode").apply { writeText("G28\nG1 X10 Y10\n".repeat(1000)) }.path

    @Test
    fun `moonraker connection test reads the klippy state`() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":{"klippy_state":"ready","moonraker_version":"v0.9"}}"""))

        val info = MoonrakerClient(config(PrintHostType.MOONRAKER, "secret"), http).testConnection().getOrThrow()

        assertEquals("Klipper ready", info.description)
        val request = server.takeRequest()
        assertEquals("/server/info", request.path)
        assertEquals("secret", request.getHeader("X-Api-Key"))
    }

    @Test
    fun `an address without a port is tried as typed, then on moonraker's port`() {
        assertEquals(
            listOf("http://192.168.1.64/", "http://192.168.1.64:7125/"),
            moonrakerCandidates("192.168.1.64").map { it.toString() },
        )
        assertEquals(listOf("http://192.168.1.64:7125/"), moonrakerCandidates("192.168.1.64:7125").map { it.toString() })
        // An explicit port 80 is the user's choice: no fallback.
        assertEquals(listOf("http://printer.local/"), moonrakerCandidates("http://printer.local:80").map { it.toString() })
    }

    @Test
    fun `moonraker falls back to the next address when the first serves only a web page`() = runTest {
        // Like port 80 on an Elegoo Neptune 4: Fluidd, which answers the API with 404.
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not found"))
        MockWebServer().use { moonraker ->
            moonraker.enqueue(MockResponse().setBody("""{"result":{"klippy_state":"ready"}}"""))
            moonraker.enqueue(MockResponse().setBody("""{"result":{"item":{"path":"cube.gcode","root":"gcodes"}}}"""))
            val client = MoonrakerClient(listOf(server.url("/"), moonraker.url("/")), null, http)

            assertEquals("Klipper ready", client.testConnection().getOrThrow().description)
            assertTrue(client.upload(gcode(), "cube.gcode", startPrint = false) { _, _ -> }.isSuccess)

            assertEquals(1, server.requestCount)
            assertEquals("/server/info", moonraker.takeRequest().path)
            assertEquals("/server/files/upload", moonraker.takeRequest().path)
        }
    }

    @Test
    fun `moonraker reports every address it tried when none answers`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        val client = MoonrakerClient(listOf(server.url("/"), server.url("/")), null, http)
        val error = client.testConnection().exceptionOrNull()
        assertTrue(error?.message.orEmpty().startsWith("No Moonraker found"), error?.message)
    }

    @Test
    fun `moonraker connection test rejects a host that is not moonraker`() = runTest {
        server.enqueue(MockResponse().setBody("""{"hello":"world"}"""))
        assertTrue(MoonrakerClient(config(PrintHostType.MOONRAKER), http).testConnection().isFailure)
    }

    @Test
    fun `moonraker upload posts the file to the gcodes root and can start the print`() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":{"klippy_state":"ready"}}"""))
        server.enqueue(MockResponse().setBody("""{"result":{"item":{"path":"cube.gcode","root":"gcodes"}}}"""))
        var lastProgress = 0L to 0L

        val result = MoonrakerClient(config(PrintHostType.MOONRAKER), http)
            .upload(gcode(), "cube.gcode", startPrint = true) { sent, total -> lastProgress = sent to total }

        assertTrue(result.isSuccess)
        assertEquals("/server/info", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/server/files/upload", request.path)
        val body = request.body.readUtf8()
        assertTrue("name=\"root\"" in body && "gcodes" in body)
        assertTrue("name=\"print\"" in body && "\r\n\r\ntrue\r\n" in body)
        assertTrue("filename=\"cube.gcode\"" in body)
        assertEquals(lastProgress.second, lastProgress.first)
        assertTrue(lastProgress.first > 0)
    }

    @Test
    fun `http errors carry the status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val error = MoonrakerClient(config(PrintHostType.MOONRAKER), http)
            .upload(gcode(), "cube.gcode", startPrint = false).exceptionOrNull()

        assertEquals(401, assertIs<PrintHostHttpException>(error).status)
    }

    @Test
    fun `octoprint connection test reads the version text`() = runTest {
        server.enqueue(MockResponse().setBody("""{"api":"0.1","server":"1.10.2","text":"OctoPrint 1.10.2"}"""))

        val info = OctoPrintClient(config(PrintHostType.OCTOPRINT, "key"), http).testConnection().getOrThrow()

        assertEquals("OctoPrint 1.10.2", info.description)
        assertEquals("/api/version", server.takeRequest().path)
    }

    @Test
    fun `octoprint upload posts to local storage and selects the file to print`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"done":true}"""))

        val result = OctoPrintClient(config(PrintHostType.OCTOPRINT, "key"), http)
            .upload(gcode(), "cube.gcode", startPrint = true)

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("/api/files/local", request.path)
        assertEquals("key", request.getHeader("X-Api-Key"))
        val body = request.body.readUtf8()
        assertTrue("name=\"select\"" in body && "name=\"print\"" in body)
    }

    @Test
    fun `factory picks the adapter for the host type`() {
        val factory = DefaultPrintHostClientFactory(
            setOf(
                com.orcamobile.core.printer.moonraker.MoonrakerAdapter(),
                com.orcamobile.core.printer.octoprint.OctoPrintAdapter(),
            ),
            http,
            com.orcamobile.core.common.CoroutineDispatchers(
                kotlinx.coroutines.Dispatchers.Default,
                kotlinx.coroutines.Dispatchers.IO,
                kotlinx.coroutines.Dispatchers.Default,
            ),
        )
        assertIs<MoonrakerClient>(assertIs<BackgroundPrintHostClient>(factory.create(config(PrintHostType.MOONRAKER))).client)
        assertIs<OctoPrintClient>(assertIs<BackgroundPrintHostClient>(factory.create(config(PrintHostType.OCTOPRINT))).client)
    }

    @Test
    fun `clients run off the calling thread`() {
        val io = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "printer-io") }
        try {
            var threadName = ""
            val recording = object : com.orcamobile.core.domain.printer.PrintHostClient {
                override suspend fun testConnection(): Result<com.orcamobile.core.model.PrintHostInfo> {
                    threadName = Thread.currentThread().name
                    return Result.success(com.orcamobile.core.model.PrintHostInfo("ok"))
                }

                override suspend fun upload(
                    localPath: String,
                    remoteName: String,
                    startPrint: Boolean,
                    options: com.orcamobile.core.model.PrintStartOptions,
                    onProgress: (sent: Long, total: Long) -> Unit,
                ) = Result.success(Unit)
            }
            val client = BackgroundPrintHostClient(recording, io.asCoroutineDispatcher())
            kotlinx.coroutines.runBlocking { client.testConnection() }
            assertEquals("printer-io", threadName)
        } finally {
            io.shutdown()
        }
    }
}
