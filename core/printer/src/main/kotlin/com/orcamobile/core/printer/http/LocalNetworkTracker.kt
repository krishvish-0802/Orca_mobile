package com.orcamobile.core.printer.http

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * The phone's current Wi-Fi or Ethernet network, followed from the moment it is
 * first asked for. Null while there is none.
 */
internal class LocalNetworkTracker(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networks = LinkedHashSet<Network>()
    private var registered = false

    @Synchronized
    fun current(): Network? {
        if (!registered) register()
        return networks.lastOrNull()
    }

    private fun register() {
        registered = true
        val manager = connectivity ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching {
            manager.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        synchronized(this@LocalNetworkTracker) {
                            networks.remove(network)
                            networks.add(network)
                        }
                    }

                    override fun onLost(network: Network) {
                        synchronized(this@LocalNetworkTracker) { networks.remove(network) }
                    }
                },
            )
        }
        // The callback reports networks a moment later; until then ask directly.
        runCatching {
            @Suppress("DEPRECATION")
            manager.allNetworks.filter { network ->
                manager.getNetworkCapabilities(network)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                } == true
            }.forEach { networks.add(it) }
        }
    }
}
