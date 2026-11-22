package com.orcamobile.core.printer

import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.model.PrintHostInfo
import com.orcamobile.core.model.PrintStartOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Runs a client's calls on [io], whatever thread they are called from. The
 * clients read response bodies and files with blocking calls once a request
 * returns; on the main thread Android refuses any of that touching the network
 * (NetworkOnMainThreadException, which has no message). A small answer that
 * arrived in one packet slipped through, a web page did not.
 */
internal class BackgroundPrintHostClient(
    internal val client: PrintHostClient,
    private val io: CoroutineDispatcher,
) : PrintHostClient {
    override suspend fun testConnection(): Result<PrintHostInfo> = withContext(io) { client.testConnection() }

    override suspend fun upload(
        localPath: String,
        remoteName: String,
        startPrint: Boolean,
        options: PrintStartOptions,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): Result<Unit> = withContext(io) { client.upload(localPath, remoteName, startPrint, options, onProgress) }
}
