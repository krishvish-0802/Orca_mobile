package com.orcamobile.core.printer.http

import com.orcamobile.core.domain.printer.PrinterConnection
import com.orcamobile.core.model.PrinterLink
import com.orcamobile.core.model.PrinterState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/**
 * A printer reached by asking: its state is read every [intervalMillis] (less
 * often while it doesn't answer), and soon after any command. For hosts whose
 * APIs are request and answer (Moonraker's and OctoPrint's HTTP APIs, and most
 * LAN print hosts), with the polling, the link state and the back-off in one place.
 *
 * Subclasses implement [read] and call [start] once constructed.
 */
internal abstract class PollingConnection(
    context: CoroutineContext,
    private val intervalMillis: Long,
    private val offlineMaxMillis: Long = 30_000,
) : PrinterConnection {
    protected val scope = CoroutineScope(SupervisorJob() + context)

    private val _state = MutableStateFlow(PrinterState())
    final override val state: StateFlow<PrinterState> = _state.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The printer's state now, from [previous] (to keep what this read doesn't cover). */
    protected abstract suspend fun read(previous: PrinterState): PrinterState

    fun start(): PollingConnection = apply {
        scope.launch {
            var failures = 0
            while (isActive) {
                try {
                    val next = read(_state.value)
                    _state.value = next.copy(link = PrinterLink.Online)
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    _state.update { it.copy(link = PrinterLink.Offline(e.message ?: e.javaClass.simpleName)) }
                }
                val wait = if (failures == 0) intervalMillis else (intervalMillis shl failures.coerceAtMost(4)).coerceAtMost(offlineMaxMillis)
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    /** Reads the state again now, rather than at the next interval: after a command. */
    protected fun refreshSoon() {
        wake.trySend(Unit)
    }

    /** Runs a command and refreshes the state after it, whatever came of it. */
    protected suspend fun command(block: suspend () -> Unit): Result<Unit> =
        runCatching { block() }.also { refreshSoon() }.onFailure { if (it is CancellationException) throw it }

    override fun close() {
        scope.cancel()
    }
}
