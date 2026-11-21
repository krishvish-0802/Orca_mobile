package com.orcamobile.core.printer.elegoo

import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.printer.PrintHostAdapter
import okhttp3.OkHttpClient
import javax.inject.Inject

class ElegooLinkAdapter @Inject constructor() : PrintHostAdapter {
    override val type = PrintHostType.ELEGOO_LINK

    override fun create(config: PrintHostConfig, http: OkHttpClient): PrintHostClient = ElegooLinkClient(config, http)
}
