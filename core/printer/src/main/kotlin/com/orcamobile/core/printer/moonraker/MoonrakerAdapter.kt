package com.orcamobile.core.printer.moonraker

import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.domain.printer.PrinterConnection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.printer.PrintHostAdapter
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import okhttp3.OkHttpClient

class MoonrakerAdapter @Inject constructor() : PrintHostAdapter {
    override val type = PrintHostType.MOONRAKER

    override fun create(config: PrintHostConfig, http: OkHttpClient): PrintHostClient = MoonrakerClient(config, http)

    override fun connect(config: PrintHostConfig, http: OkHttpClient, context: CoroutineContext): PrinterConnection =
        MoonrakerConnection(MoonrakerApi(moonrakerCandidates(config.baseUrl), config.apiKey, http), context).start()
}
