package com.orcamobile.core.printer

import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostType
import okhttp3.OkHttpClient

/**
 * Creates clients for one [PrintHostType]. Each protocol registers one adapter
 * (see di/PrinterModule.kt), so adding a protocol adds a class and a binding,
 * without touching the factory.
 */
interface PrintHostAdapter {
    val type: PrintHostType

    fun create(config: PrintHostConfig, http: OkHttpClient): PrintHostClient
}
