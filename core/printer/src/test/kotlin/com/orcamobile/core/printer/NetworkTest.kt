package com.orcamobile.core.printer

import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.printer.elegoo.ElegooLinkClient
import com.orcamobile.core.printer.http.LanSocketFactory
import com.orcamobile.core.printer.http.PrintHostHttpException
import com.orcamobile.core.printer.http.isOnLan
import com.orcamobile.core.printer.http.withNetworkRetries
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkTest {

    @Test
    fun `knows which addresses only the local network reaches`() {
        for (lan in listOf("192.168.1.42", "10.0.0.7", "172.16.5.1", "169.254.10.1", "fe80::1", "fd12:3456::1")) {
            assertTrue(InetAddress.getByName(lan).isOnLan(), lan)
        }
        for (other in listOf("8.8.8.8", "127.0.0.1", "100.64.0.1", "172.32.0.1", "2001:4860::8888")) {
            assertFalse(InetAddress.getByName(other).isOnLan(), other)
        }
    }

    @Test
    fun `LAN sockets are bound to the local network, others are not`() {
        val bound = mutableListOf<Socket>()
        val factory = LanSocketFactory(localNetwork = { "wifi" }) { _: String, socket -> bound += socket }

        val lan = factory.createSocket()
        runCatching { lan.connect(InetSocketAddress("192.168.255.254", 9), 1) }
        assertEquals(listOf(lan), bound)

        MockWebServer().use { server ->
            server.start()
            factory.createSocket().use { it.connect(InetSocketAddress(server.hostName, server.port), 1_000) }
        }
        assertEquals(1, bound.size)
    }

    @Test
    fun `without a local network LAN sockets use the default route`() {
        var binds = 0
        val factory = LanSocketFactory<String>(localNetwork = { null }) { _, _ -> binds++ }
        runCatching { factory.createSocket().connect(InetSocketAddress("192.168.255.254", 9), 1) }
        assertEquals(0, binds)
    }

    @Test
    fun `retries a flaky network, not an answer`() = runBlocking {
        var calls = 0
        val value = withNetworkRetries("printer", pauseMillis = 1) {
            if (++calls < 3) throw ConnectException("failed to connect (ECONNREFUSED)")
            "ok"
        }
        assertEquals("ok", value)

        calls = 0
        assertFailsWith<PrintHostHttpException> {
            withNetworkRetries("printer", pauseMillis = 1) {
                calls++
                throw PrintHostHttpException(404, "")
            }
        }
        assertEquals(1, calls)

        val error = assertFailsWith<IOException> {
            withNetworkRetries("192.168.1.42", pauseMillis = 1) { throw ConnectException("failed to connect (ECONNREFUSED)") }
        }
        assertTrue(error.message.orEmpty().startsWith("192.168.1.42 refused the connection"), error.message)
    }

    @Test
    fun `an Elegoo printer that drops the first connection still tests fine`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setBody("<title>ELEGOO</title>"))
            server.start()
            val http = OkHttpClient.Builder()
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .retryOnConnectionFailure(false)
                .build()
            val client = ElegooLinkClient(PrintHostConfig(PrintHostType.ELEGOO_LINK, server.url("/").toString(), null), http)
            assertEquals("Elegoo printer (ElegooLink)", client.testConnection().getOrThrow().description)
        }
    }
}
