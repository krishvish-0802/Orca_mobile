package com.orcamobile.core.printer.octoprint

import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.domain.printer.PrinterConnection
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.printer.PrintHostAdapter
import com.orcamobile.core.printer.http.normalizeBaseUrl
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import okhttp3.OkHttpClient

class OctoPrintAdapter @Inject constructor() : PrintHostAdapter {
    override val type = PrintHostType.OCTOPRINT

    override fun create(config: PrintHostConfig, http: OkHttpClient): PrintHostClient = OctoPrintClient(config, http)

    override fun connect(config: PrintHostConfig, http: OkHttpClient, context: CoroutineContext): PrinterConnection =
        OctoPrintConnection(normalizeBaseUrl(config.baseUrl), config.apiKey, http, context).start()
}
