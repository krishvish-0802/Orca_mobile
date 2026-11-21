package com.orcamobile.core.printer.http

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An HTTP error from a printer host, with the status and the start of the body. */
class PrintHostHttpException(val status: Int, body: String) :
    IOException("HTTP $status${if (body.isBlank()) "" else ": ${body.take(300)}"}")

/** Runs the call without blocking a thread; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response)
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
    })
}

/** Reads the body as text, throwing [PrintHostHttpException] on a non-2xx status. */
internal fun Response.bodyOrThrow(): String = use {
    val text = body?.string().orEmpty()
    if (!isSuccessful) throw PrintHostHttpException(code, text)
    text
}

/** `192.168.1.50:7125` → `http://192.168.1.50:7125/`; keeps an explicit scheme. */
internal fun normalizeBaseUrl(raw: String): HttpUrl {
    val trimmed = raw.trim().trimEnd('/')
    require(trimmed.isNotEmpty()) { "The printer address is empty" }
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    return "$withScheme/".toHttpUrl()
}

/** Whether the user typed a port: `host:7125` or `http://host:80/`, not `host` or `http://host/`. */
internal fun hasExplicitPort(raw: String): Boolean {
    val authority = raw.trim().substringAfter("://").substringBefore('/')
    // An IPv6 literal keeps its colons inside brackets.
    return authority.substringAfterLast(']').contains(':')
}

internal fun Request.Builder.apiKey(key: String?): Request.Builder =
    if (key.isNullOrBlank()) this else header("X-Api-Key", key)
