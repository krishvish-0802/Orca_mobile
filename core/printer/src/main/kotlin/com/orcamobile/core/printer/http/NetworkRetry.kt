package com.orcamobile.core.printer.http

import kotlinx.coroutines.delay
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Tries for a connection test: a printer waking its Wi-Fi often misses the first. */
internal const val CONNECTION_ATTEMPTS = 3

/**
 * Runs [block], again after a short pause when it fails the way a flaky
 * network does (refused, unreachable, reset, timed out, cut short), at most
 * [attempts] times. Answers from the host (an HTTP error, the wrong kind of
 * printer) are not retried. The last failure is described for people.
 */
internal suspend fun <T> withNetworkRetries(
    host: String,
    attempts: Int = CONNECTION_ATTEMPTS,
    pauseMillis: Long = 700L,
    block: suspend () -> T,
): T {
    var last: IOException? = null
    repeat(attempts) { attempt ->
        try {
            return block()
        } catch (e: IOException) {
            if (!e.isTransient()) throw e
            last = e
            if (attempt < attempts - 1) delay(pauseMillis * (attempt + 1))
        }
    }
    throw describe(checkNotNull(last), host)
}

internal fun IOException.isTransient(): Boolean = when (this) {
    is PrintHostHttpException -> false
    is UnknownHostException -> true
    is SocketException, is SocketTimeoutException, is EOFException -> true
    else -> message?.contains("unexpected end of stream", ignoreCase = true) == true ||
        cause.let { it is IOException && it !== this && it.isTransient() }
}

/** A failure to reach [host], in words that say what to check. */
internal fun describe(e: IOException, host: String): IOException {
    val message = when {
        e is ConnectException && e.message.orEmpty().contains("ECONNREFUSED") ->
            "$host refused the connection. Check the address and port, and that the printer's network service is on."
        e is NoRouteToHostException || e.message.orEmpty().contains("ENETUNREACH") || e.message.orEmpty().contains("EHOSTUNREACH") ->
            "Can't reach $host. Check that the phone is on the same Wi-Fi as the printer and the printer is on."
        e is SocketTimeoutException || e.message.orEmpty().contains("timed out", ignoreCase = true) ->
            "$host did not answer in time. Check that the printer is on and connected to Wi-Fi."
        e is UnknownHostException -> "Can't find $host on the network. Try the printer's IP address."
        else -> "Can't connect to $host: ${e.message ?: e.javaClass.simpleName}"
    }
    return IOException(message, e)
}
