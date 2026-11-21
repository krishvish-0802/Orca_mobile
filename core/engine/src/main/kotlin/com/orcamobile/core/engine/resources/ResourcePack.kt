package com.orcamobile.core.engine.resources

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Reads the `.opk` resource pack written by build-logic's PackEngineResourcesTask
 * (the format is documented there): a gzip stream of `"OPK1"`, an entry count,
 * then `{u16 path length, path, u32 data length, data}` per file.
 */
internal object ResourcePack {
    private val MAGIC = "OPK1".toByteArray(Charsets.US_ASCII)

    /**
     * Writes every entry of [input] under [targetDir] and returns the number of
     * files written. Throws [IOException] on a malformed pack or on an entry
     * whose path would escape [targetDir].
     */
    fun unpack(input: InputStream, targetDir: File): Int {
        val root = targetDir.canonicalFile
        DataInputStream(GZIPInputStream(input.buffered(), 1 shl 16)).use { data ->
            val magic = ByteArray(MAGIC.size).also { data.readFully(it) }
            if (!magic.contentEquals(MAGIC)) throw IOException("Not an engine resource pack")
            val count = data.readInt()
            if (count < 0) throw IOException("Corrupt resource pack: negative entry count")
            val buffer = ByteArray(1 shl 16)
            repeat(count) {
                val path = ByteArray(data.readUnsignedShort()).also { data.readFully(it) }.toString(Charsets.UTF_8)
                val target = resolveInside(root, path)
                var remaining = data.readInt()
                if (remaining < 0) throw IOException("Corrupt resource pack entry: $path")
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    while (remaining > 0) {
                        val read = data.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read < 0) throw IOException("Truncated resource pack entry: $path")
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            return count
        }
    }

    private fun resolveInside(root: File, path: String): File {
        if (path.isEmpty() || path.startsWith("/") || path.split('/').any { it == ".." }) {
            throw IOException("Unsafe path in resource pack: $path")
        }
        val target = File(root, path).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) {
            throw IOException("Unsafe path in resource pack: $path")
        }
        return target
    }
}
