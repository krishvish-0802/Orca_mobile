package com.orcamobile.core.domain.printer

import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.SavedPrinter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One live connection per saved printer, shared by every screen (and the
 * notifications) that follows it. A printer whose address changed gets a new
 * connection; one no longer kept is closed by [keepOnly].
 */
@Singleton
class PrinterSessions @Inject constructor(private val factory: PrinterConnectionFactory) {
    private val open = mutableMapOf<String, Pair<PrintHostConfig, PrinterConnection?>>()

    /** The connection to [printer], opened when first asked for; null where its protocol has none. */
    @Synchronized
    fun connection(printer: SavedPrinter): PrinterConnection? {
        open[printer.id]?.let { (host, connection) -> if (host == printer.host) return connection else connection?.close() }
        val connection = factory.connect(printer.host)
        open[printer.id] = printer.host to connection
        return connection
    }

    /** Closes the connections to printers not in [ids]. */
    @Synchronized
    fun keepOnly(ids: Set<String>) {
        val gone = open.keys - ids
        gone.forEach { open.remove(it)?.second?.close() }
    }
}
