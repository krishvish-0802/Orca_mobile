package com.orcamobile.core.printer.http

import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * Sockets for printer hosts that go out over the local network (Wi-Fi or
 * Ethernet, from [localNetwork]) when they connect to a LAN address.
 *
 * Android sends a socket over the default network, and that is mobile data
 * whenever the phone decides Wi-Fi is weak or has no internet (Samsung's
 * "Switch to mobile data", Android's Adaptive Wi-Fi), so a printer on the LAN
 * becomes unreachable for a while and the first tries fail at once. A socket
 * bound to the Wi-Fi network reaches it regardless. Addresses outside the LAN
 * (a remote OctoPrint, a VPN such as Tailscale) keep the default route.
 *
 * [bind] binds a socket to a network; it is the platform's
 * android.net.Network.bindSocket, injected so this stays testable on the JVM.
 */
internal class LanSocketFactory<N : Any>(
    private val localNetwork: () -> N?,
    private val bind: (N, Socket) -> Unit,
) : SocketFactory() {

    override fun createSocket(): Socket = LanSocket()

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }

    private inner class LanSocket : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val address = (endpoint as? InetSocketAddress)?.address
            if (address != null && address.isOnLan()) {
                // Without a local network the default route is all there is.
                localNetwork()?.let { network -> runCatching { bind(network, this) } }
            }
            super.connect(endpoint, timeout)
        }
    }
}

/** Private, link-local or unique-local: an address only the local network can reach. */
internal fun InetAddress.isOnLan(): Boolean {
    if (isSiteLocalAddress || isLinkLocalAddress) return true
    // IPv6 unique local addresses, fc00::/7.
    return this is Inet6Address && (address[0].toInt() and 0xFE) == 0xFC
}
