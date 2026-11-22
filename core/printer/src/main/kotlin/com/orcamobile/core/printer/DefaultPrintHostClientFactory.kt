package com.orcamobile.core.printer

import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.domain.printer.PrintHostClientFactory
import com.orcamobile.core.model.PrintHostConfig
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultPrintHostClientFactory @Inject constructor(
    adapters: Set<@JvmSuppressWildcards PrintHostAdapter>,
    private val http: OkHttpClient,
    private val dispatchers: CoroutineDispatchers,
) : PrintHostClientFactory {
    private val byType = adapters.associateBy { it.type }

    override fun create(config: PrintHostConfig): PrintHostClient {
        val adapter = byType[config.type] ?: error("No adapter registered for ${config.type}")
        return BackgroundPrintHostClient(adapter.create(config, http), dispatchers.io)
    }
}
