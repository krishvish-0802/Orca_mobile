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
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Hosts on slow SD cards can take a while to accept a large upload.
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()
}
