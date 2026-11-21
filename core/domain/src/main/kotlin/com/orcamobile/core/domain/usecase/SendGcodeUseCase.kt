package com.orcamobile.core.domain.usecase

import com.orcamobile.core.domain.printer.PrintHostClientFactory
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintStartOptions
import java.io.File
import javax.inject.Inject

/** Uploads a sliced G-code file to a printer host and, if asked, starts printing it. */
class SendGcodeUseCase @Inject constructor(
    private val clients: PrintHostClientFactory,
) {
    suspend operator fun invoke(
        host: PrintHostConfig,
        gcodePath: String,
        startPrint: Boolean,
        options: PrintStartOptions = PrintStartOptions(),
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<Unit> = clients.create(host).upload(gcodePath, File(gcodePath).name, startPrint, options, onProgress)
}
