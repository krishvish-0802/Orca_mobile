package com.orcamobile.core.printer.http

import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.printer.CameraFrames
import com.orcamobile.core.model.CameraFeed
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Printer cameras over HTTP: an MJPEG stream (`multipart/x-mixed-replace`, as
 * the Centauri Carbon, Klipper's crowsnest and OctoPrint's mjpg-streamer serve)
 * read frame by frame, or JPEG snapshots fetched one after another.
 */
@Singleton
class HttpCameraFrames @Inject constructor(
    http: OkHttpClient,
    private val dispatchers: CoroutineDispatchers,
) : CameraFrames {
    // A stream runs as long as it is watched.
    private val http = http.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()

    override fun frames(feed: CameraFeed): Flow<ByteArray> = when (feed) {
        is CameraFeed.Mjpeg -> mjpeg(feed.url)
        is CameraFeed.Snapshots -> snapshots(feed)
    }.flowOn(dispatchers.io)

    private fun mjpeg(url: String): Flow<ByteArray> = flow {
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("The camera answered ${it.code}")
            val body = it.body ?: throw IOException("The camera sent nothing")
            val reader = MjpegReader(body.byteStream())
            while (currentCoroutineContext().isActive) {
                emit(reader.next() ?: break)
            }
        }
    }

    private fun snapshots(feed: CameraFeed.Snapshots): Flow<ByteArray> = flow {
        while (currentCoroutineContext().isActive) {
            http.newCall(Request.Builder().url(feed.url).build()).execute().use {
                if (!it.isSuccessful) throw IOException("The camera answered ${it.code}")
                emit(it.body?.bytes() ?: throw IOException("The camera sent nothing"))
            }
            delay(feed.intervalMillis)
        }
    }
}

/**
 * JPEG frames out of an MJPEG stream. Rather than trusting each part's
 * headers (cameras disagree on them), it looks for each JPEG's start (FF D8)
 * and end (FF D9) markers.
 */
internal class MjpegReader(private val input: InputStream, private val maxFrameBytes: Int = 8 * 1024 * 1024) {
    private val buffer = java.io.ByteArrayOutputStream(256 * 1024)

    /** The next frame, or null when the stream ends. */
    fun next(): ByteArray? {
        var previous = -1
        var inFrame = false
        buffer.reset()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (!inFrame) {
                if (previous == 0xFF && b == 0xD8) {
                    inFrame = true
                    buffer.write(0xFF)
                    buffer.write(0xD8)
                }
            } else {
                buffer.write(b)
                if (previous == 0xFF && b == 0xD9) return buffer.toByteArray()
                if (buffer.size() > maxFrameBytes) throw IOException("The camera sent a frame too large to show")
            }
            previous = b
        }
    }
}
