package com.orcamobile.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.model.TextureImageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes a picked image with Android's decoders and keeps it as a PNG, the one
 * format libslic3r's texture code reads (decode_height_texture). Large photos
 * are scaled down on the way: a bake samples the texture per vertex, so more
 * pixels than that only cost memory.
 */
@Singleton
class ContentTextureImageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
) : TextureImageRepository {

    override suspend fun import(uri: String, maxSidePx: Int): Result<Pair<String, String>> = withContext(dispatchers.io) {
        runCatching {
            val source = Uri.parse(uri)
            val name = (displayName(source) ?: source.lastPathSegment ?: "texture").substringBeforeLast('.')
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(source).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("This file is not an image the phone can read")
            // Decode at the smallest power of two still at least maxSidePx, then scale exactly.
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSidePx) sample *= 2
            val decoded = open(source).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: throw IOException("This file is not an image the phone can read")
            val longer = max(decoded.width, decoded.height)
            val bitmap = if (longer > maxSidePx) {
                val scale = maxSidePx.toFloat() / longer
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt().coerceAtLeast(1), (decoded.height * scale).roundToInt().coerceAtLeast(1), true)
                    .also { if (it !== decoded) decoded.recycle() }
            } else {
                decoded
            }
            val dir = File(context.filesDir, "textures").apply { mkdirs() }
            val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "texture" }
            val target = File(dir, "${System.currentTimeMillis()}_$safeName.png")
            target.outputStream().buffered().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("The image could not be saved")
            }
            bitmap.recycle()
            target.absolutePath to name
        }
    }

    private fun open(uri: Uri) = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the image")

    private fun displayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
}
