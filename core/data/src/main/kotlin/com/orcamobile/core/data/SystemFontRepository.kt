package com.orcamobile.core.data

import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.model.FontInfo
import com.orcamobile.core.domain.model.FontRepository
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The fonts Android ships in /system/fonts (and /product/fonts): TrueType and
 * OpenType files, named from their file names ("NotoSerif-Bold.ttf" is
 * "Noto Serif Bold"). Fonts for one script only (emoji, CJK, ...) come last.
 */
@Singleton
class SystemFontRepository @Inject constructor(
    private val dispatchers: CoroutineDispatchers,
) : FontRepository {

    private var cached: List<FontInfo>? = null

    override suspend fun fonts(): List<FontInfo> = cached ?: withContext(dispatchers.io) {
        FONT_DIRS.asSequence()
            .map(::File)
            .flatMap { dir -> dir.listFiles()?.asSequence() ?: emptySequence() }
            .filter { it.isFile && it.canRead() && it.extension.lowercase() in EXTENSIONS }
            .distinctBy { it.name }
            .map { FontInfo(displayName(it.nameWithoutExtension), it.path) }
            .sortedWith(compareBy<FontInfo> { rank(it.name) }.thenBy { it.name })
            .toList()
            .also { cached = it }
    }

    companion object {
        private val FONT_DIRS = listOf("/system/fonts", "/product/fonts", "/system/product/fonts")
        private val EXTENSIONS = setOf("ttf", "otf", "ttc")

        /** "NotoSerif-BoldItalic" → "Noto Serif Bold Italic". */
        internal fun displayName(stem: String): String =
            stem.replace('_', ' ').replace('-', ' ')
                .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        /** Familiar Latin faces first, script-specific ones last. */
        internal fun rank(name: String): Int = when {
            name.startsWith("Roboto") -> 0
            name.startsWith("Noto Sans") && name.split(' ').size <= 4 && SCRIPT_WORDS.none { it in name } -> 1
            name.startsWith("Noto Serif") && SCRIPT_WORDS.none { it in name } -> 2
            SCRIPT_WORDS.any { it in name } || name.contains("Emoji") || name.contains("CJK") -> 9
            else -> 5
        }

        private val SCRIPT_WORDS = listOf(
            "Arabic", "Armenian", "Bengali", "Devanagari", "Ethiopic", "Georgian", "Gujarati", "Gurmukhi", "Hebrew",
            "Kannada", "Khmer", "Lao", "Malayalam", "Myanmar", "Oriya", "Sinhala", "Tamil", "Telugu", "Thai", "Tibetan",
            "Symbols", "Math", "Music", "Cherokee", "Mongolian", "Syriac", "Thaana", "Tifinagh", "Yi", "Javanese",
        )
    }
}
