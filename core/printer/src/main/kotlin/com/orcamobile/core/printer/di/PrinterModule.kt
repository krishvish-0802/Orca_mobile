package com.orcamobile.core.printer.di

import com.orcamobile.core.domain.printer.PrintHostClientFactory
import com.orcamobile.core.printer.DefaultPrintHostClientFactory
import com.orcamobile.core.printer.PrintHostAdapter
import com.orcamobile.core.printer.elegoo.ElegooLinkAdapter
import com.orcamobile.core.printer.moonraker.MoonrakerAdapter
import com.orcamobile.core.printer.octoprint.OctoPrintAdapter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import android.content.Context
import android.net.Network
import com.orcamobile.core.printer.http.LanSocketFactory
import com.orcamobile.core.printer.http.LocalNetworkTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface PrinterModule {
    @Binds
    fun clientFactory(impl: DefaultPrintHostClientFactory): PrintHostClientFactory

    @Binds
    @IntoSet
    fun moonraker(adapter: MoonrakerAdapter): PrintHostAdapter

    @Binds
    @IntoSet
    fun octoPrint(adapter: OctoPrintAdapter): PrintHostAdapter

    @Binds
    @IntoSet
    fun elegooLink(adapter: ElegooLinkAdapter): PrintHostAdapter
}

@Module
@InstallIn(SingletonComponent::class)
internal object PrinterNetworkModule {
    @Provides
    @Singleton
    fun okHttp(@ApplicationContext context: Context): OkHttpClient {
        val localNetwork = LocalNetworkTracker(context)
        return OkHttpClient.Builder()
            // A printer on the LAN is reached over Wi-Fi even while Android
            // prefers mobile data (see LanSocketFactory).
            .socketFactory(LanSocketFactory(localNetwork::current) { network: Network, socket -> network.bindSocket(socket) })
            // A fresh connection per request, as desktop OrcaSlicer's curl makes:
            // printers' small web servers drop idle connections without notice.
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(10, TimeUnit.SECONDS)
            // Hosts on slow SD cards can take a while to accept a large upload.
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()
    }
}
