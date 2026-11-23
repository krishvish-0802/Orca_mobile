package com.orcamobile.core.domain.model

/** A font file text can be embossed in. */
data class FontInfo(val name: String, val path: String)

/** The fonts on the device, for embossing text. */
interface FontRepository {
    /** Readable fonts, the plain Latin ones first. */
    suspend fun fonts(): List<FontInfo>
}
