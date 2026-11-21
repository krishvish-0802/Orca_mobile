package com.orcamobile.core.domain.usecase

import com.orcamobile.core.domain.printer.PrintHostClientFactory
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostInfo
import javax.inject.Inject

/** Checks that a printer host is reachable and speaks the protocol its type says. */
class TestPrintHostUseCase @Inject constructor(
    private val clients: PrintHostClientFactory,
) {
    suspend operator fun invoke(host: PrintHostConfig): Result<PrintHostInfo> = clients.create(host).testConnection()
}
