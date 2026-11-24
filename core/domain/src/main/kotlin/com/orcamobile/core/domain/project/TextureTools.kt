package com.orcamobile.core.domain.project

import com.orcamobile.core.model.LibraryTexture
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.TextureBake
import com.orcamobile.core.model.TextureLayerSettings
import com.orcamobile.core.model.TextureStack

/**
 * The desktop's texture displacement (GLGizmoTextureDisplacement), run by the
 * engine on a loaded project: a stack of height-map layers per object, each
 * painted where it applies (PaintKind.TEXTURE), baked into real geometry.
 * Every call returns the object's stack after it.
 */
interface TextureTools {
    suspend fun stack(project: ProjectId, objectIndex: Int): Result<TextureStack>

    /** A layer with the PNG at [imagePath]; returns the stack and the new layer's slot. */
    suspend fun addLayer(project: ProjectId, objectIndex: Int, imagePath: String, name: String): Result<Pair<TextureStack, Int>>

    /** The layer's texture swapped for the PNG at [imagePath]; its settings and paint stay. */
    suspend fun setImage(project: ProjectId, objectIndex: Int, slot: Int, imagePath: String, name: String): Result<TextureStack>

    suspend fun setLayer(project: ProjectId, objectIndex: Int, slot: Int, settings: TextureLayerSettings): Result<TextureStack>

    /** Removes the layer and its paint. */
    suspend fun removeLayer(project: ProjectId, objectIndex: Int, slot: Int): Result<TextureStack>

    /** Moves the layer [by] places toward the top (positive) or bottom of the stack. */
    suspend fun moveLayer(project: ProjectId, objectIndex: Int, slot: Int, by: Int): Result<TextureStack>

    suspend fun setOptions(
        project: ProjectId,
        objectIndex: Int,
        smooth: Boolean,
        smoothStrength: Float,
        smoothPasses: Int,
        displaceBorder: Boolean,
    ): Result<TextureStack>

    /** Paints the whole object with the layer, or ([erase]) none of it. */
    suspend fun paintAll(project: ProjectId, objectIndex: Int, slot: Int, erase: Boolean = false): Result<TextureStack>

    /**
     * Displaces the object's painted parts by the stack: the relief becomes
     * their meshes and the layers' paint is cleared (the layers stay).
     * [onProgress] gets 0..100. Cancelling the caller's coroutine stops it with
     * nothing changed.
     */
    suspend fun bake(project: ProjectId, objectIndex: Int, onProgress: (Int) -> Unit = {}): Result<TextureBake>

    /** The textures shipped with the app, by name. */
    suspend fun library(): Result<List<LibraryTexture>>
}
