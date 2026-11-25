package com.orcamobile.core.printer.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.printer.PrinterDiscovery
import com.orcamobile.core.model.DiscoveredPrinter
import com.orcamobile.core.printer.http.LocalNetworkTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/** One protocol's way of finding its printers on the local network. */
interface PrinterDiscoverer {
    fun discover(timeoutMillis: Long): Flow<DiscoveredPrinter>
}

/** Every protocol's discovery at once; each printer once. */
@Singleton
class CompositePrinterDiscovery @Inject constructor(
    private val discoverers: Set<@JvmSuppressWildcards PrinterDiscoverer>,
) : PrinterDiscovery {
    override fun discover(timeoutMillis: Long): Flow<DiscoveredPrinter> = channelFlow {
        val seen = mutableSetOf<String>()
        merge(*discoverers.map { it.discover(timeoutMillis) }.toTypedArray()).collect { printer ->
            val key = printer.uniqueId ?: "${printer.host.type}/${printer.host.baseUrl}"
            if (seen.add(key)) send(printer)
        }
    }
}

/** A UDP answer: its text, and the address it came from. */
data class BroadcastReply(val text: String, val from: String)

/** UDP broadcasts on the phone's Wi-Fi, and the answers that come back. */
@Singleton
internal class Broadcasts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val network: LocalNetworkTracker,
    private val dispatchers: CoroutineDispatchers,
) {
    /** Sends [message] to [port] on every broadcast address of the Wi-Fi network, and reads answers for [timeoutMillis]. */
    fun ask(message: ByteArray, port: Int, timeoutMillis: Long): Flow<BroadcastReply> = channelFlow {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("orca-discovery")?.apply { setReferenceCounted(false) }
        runCatching { lock?.acquire() }
        val socket = DatagramSocket()
        try {
            socket.broadcast = true
            socket.soTimeout = SLICE_MILLIS
            network.current()?.let { runCatching { it.bindSocket(socket) } }
            for (address in broadcastAddresses()) {
                runCatching { socket.send(DatagramPacket(message, message.size, address, port)) }
            }
            val deadline = System.currentTimeMillis() + timeoutMillis
            val buffer = ByteArray(8 * 1024)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                send(BroadcastReply(String(packet.data, packet.offset, packet.length), packet.address.hostAddress.orEmpty()))
            }
        } finally {
            socket.close()
            runCatching { lock?.release() }
        }
    }.flowOn(dispatchers.io)

    /** The Wi-Fi subnet's broadcast address, and the limited broadcast (255.255.255.255). */
    private fun broadcastAddresses(): List<InetAddress> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val subnet = network.current()?.let { connectivity?.getLinkProperties(it) }?.linkAddresses.orEmpty()
            .filter { it.address is Inet4Address }
            .map { link ->
                val bytes = link.address.address
                val host = 32 - link.prefixLength
                val ip = bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                val mask = if (host >= 32) 0L else (0xFFFFFFFFL shl host) and 0xFFFFFFFFL
                val broadcast = (ip and mask) or (mask.inv() and 0xFFFFFFFFL)
                InetAddress.getByAddress(ByteArray(4) { i -> (broadcast shr (24 - 8 * i)).toByte() })
            }
        return (subnet + InetAddress.getByName("255.255.255.255")).distinct()
    }

    private companion object {
        const val SLICE_MILLIS = 250
    }
}
