package com.orcamobile.core.printer.octoprint

import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.printer.PrintHostAdapter
import okhttp3.OkHttpClient
import javax.inject.Inject

class OctoPrintAdapter @Inject constructor() : PrintHostAdapter {
    override val type = PrintHostType.OCTOPRINT

    override fun create(config: PrintHostConfig, http: OkHttpClient): PrintHostClient = OctoPrintClient(config, http)
}
