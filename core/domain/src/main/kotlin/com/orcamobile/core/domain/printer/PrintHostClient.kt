package com.orcamobile.core.domain.printer

import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostInfo

/** Talks to one printer host (Moonraker, OctoPrint, ...). Failures come back as a failed [Result]. */
interface PrintHostClient {
    /** Checks that the host is reachable and is what its type says. */
    suspend fun testConnection(): Result<PrintHostInfo>

    /**
     * Uploads the G-code at [localPath] as [remoteName], optionally starting the print.
     * [onProgress] receives bytes sent and the total, on a background thread.
     */
    suspend fun upload(
        localPath: String,
        remoteName: String,
        startPrint: Boolean,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<Unit>
}

/** Creates the [PrintHostClient] for a host's protocol. */
interface PrintHostClientFactory {
    fun create(config: PrintHostConfig): PrintHostClient
}
