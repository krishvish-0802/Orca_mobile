package com.orcamobile.core.domain.model

/** Brings an image the user picked into app storage as a texture the engine reads (a PNG). */
interface TextureImageRepository {
    /**
     * Copies the image at [uri] (a content URI; PNG, JPEG, WebP...) as a PNG no
     * larger than [maxSidePx] on its longer side. Returns its path and a name
     * for it (the file's, without extension).
     */
    suspend fun import(uri: String, maxSidePx: Int = DEFAULT_MAX_SIDE_PX): Result<Pair<String, String>>

    companion object {
        /** Big enough for fine detail at a few cm, small enough to bake quickly on a phone. */
        const val DEFAULT_MAX_SIDE_PX = 2048
    }
}
