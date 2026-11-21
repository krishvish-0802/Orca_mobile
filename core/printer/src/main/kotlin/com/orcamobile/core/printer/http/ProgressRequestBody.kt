package com.orcamobile.core.printer.http

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File

/** Streams a file as a request body and reports bytes written. */
internal class ProgressRequestBody(
    private val file: File,
    private val onProgress: (sent: Long, total: Long) -> Unit,
    private val mediaType: MediaType = "application/octet-stream".toMediaType(),
) : RequestBody() {
    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var sent = 0L
        file.source().use { source ->
            while (true) {
                val read = source.read(sink.buffer, SEGMENT)
                if (read == -1L) break
                sink.flush()
                sent += read
                onProgress(sent, total)
            }
        }
    }

    private companion object {
        const val SEGMENT = 64L * 1024
    }
}
