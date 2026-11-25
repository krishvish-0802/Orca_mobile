package com.orcamobile.core.printer

import com.orcamobile.core.printer.http.MjpegReader
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import org.junit.Test

class MjpegReaderTest {

    @Test
    fun `frames are cut out of the stream at their JPEG markers`() {
        val frame1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val frame2 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0xD9.toByte())
        val stream = "--frame\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + frame1 +
            "\r\n--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 7\r\n\r\n".toByteArray() + frame2 + "\r\n--frame".toByteArray()
        val reader = MjpegReader(stream.inputStream())
        assertContentEquals(frame1, reader.next())
        assertContentEquals(frame2, reader.next())
        assertNull(reader.next())
    }
}
