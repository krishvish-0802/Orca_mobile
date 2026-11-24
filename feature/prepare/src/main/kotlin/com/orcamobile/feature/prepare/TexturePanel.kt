package com.orcamobile.feature.prepare

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.model.TextureBlend
import com.orcamobile.core.model.TextureLayer
import com.orcamobile.core.model.TextureLayerSettings
import com.orcamobile.core.model.TextureProjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The Paint tool's Texture kind (the desktop's texture displacement panel): the
 * object's textures, the active one's settings, painting it over the whole
 * object, and the bake that turns the painted textures into relief.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TextureSection(texture: TextureUiState, onIntent: (TextureIntent) -> Unit, onPickFile: () -> Unit) {
    val baking = texture.bakingPercent != null
    val enabled = !texture.busy && !baking

    Text(stringResource(R.string.prepare_texture_layers), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Top of the stack first, as the desktop lists them.
        for (layer in texture.stack.layers.asReversed()) {
            FilterChip(
                selected = texture.active == layer.slot,
                onClick = { onIntent(TextureIntent.Select(layer.slot)) },
                enabled = enabled,
                label = {
                    Text(
                        if (layer.painted) "${layer.name} · ${stringResource(R.string.prepare_texture_painted)}" else layer.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = { TextureThumbnail(layer.path, 20.dp) },
            )
        }
        FilterChip(
            selected = texture.choosing == TextureChoice.NewLayer,
            onClick = { onIntent(TextureIntent.Choose(TextureChoice.NewLayer)) },
            enabled = enabled && !texture.stack.full,
            label = { Text(stringResource(R.string.prepare_texture_add)) },
        )
    }

    texture.choosing?.let { choice ->
        TexturePicker(texture, onPick = { onIntent(TextureIntent.PickLibrary(it)) }, onPickFile = onPickFile) {
            onIntent(TextureIntent.Choose(null))
        }
        if (choice == TextureChoice.NewLayer) return
    }

    val layer = texture.activeLayer
    if (layer == null) {
        Text(stringResource(R.string.prepare_texture_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LayerSettings(texture, layer, enabled, onIntent)

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TonalPillButton(
            stringResource(R.string.prepare_texture_all),
            onClick = { onIntent(TextureIntent.PaintAll(erase = false)) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        TonalPillButton(
            stringResource(R.string.prepare_texture_none_painted),
            onClick = { onIntent(TextureIntent.PaintAll(erase = true)) },
            enabled = enabled && layer.painted,
            modifier = Modifier.weight(1f),
        )
    }

    texture.bakingPercent?.let { percent ->
        Text(stringResource(R.string.prepare_texture_baking, percent), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        TonalPillButton(stringResource(R.string.prepare_texture_stop), onClick = { onIntent(TextureIntent.CancelBake) }, modifier = Modifier.fillMaxWidth())
    } ?: PrimaryPillButton(
        stringResource(R.string.prepare_texture_bake),
        onClick = { onIntent(TextureIntent.Bake) },
        enabled = enabled && texture.stack.anyPainted,
        modifier = Modifier.fillMaxWidth(),
    )
    if (!texture.stack.anyPainted && !baking) {
        Text(stringResource(R.string.prepare_texture_bake_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    texture.lastBake?.let { bake ->
        Text(
            stringResource(R.string.prepare_texture_baked, count(bake.trianglesBefore), count(bake.trianglesAfter)) +
                if (bake.budgetLimited) " " + stringResource(R.string.prepare_texture_budget) else "",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    texture.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { onIntent(TextureIntent.DismissError) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayerSettings(texture: TextureUiState, layer: TextureLayer, enabled: Boolean, onIntent: (TextureIntent) -> Unit) {
    val s = layer.settings
    val edit: (TextureLayerSettings) -> Unit = { onIntent(TextureIntent.Edit(it)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextureThumbnail(layer.path, 56.dp)
        Column(Modifier.weight(1f)) {
            Text(layer.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (layer.widthPx > 0) Text("${layer.widthPx} × ${layer.heightPx}", style = MaterialTheme.typography.bodySmall)
            if (layer.hasColour) Text(stringResource(R.string.prepare_texture_colour_note), style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onIntent(TextureIntent.Choose(TextureChoice.Replace(layer.slot))) }, enabled = enabled) {
            Text(stringResource(R.string.prepare_texture_change))
        }
        TextButton(onClick = { onIntent(TextureIntent.Remove(layer.slot)) }, enabled = enabled) {
            Text(stringResource(R.string.prepare_texture_remove))
        }
    }

    Text(stringResource(R.string.prepare_texture_depth, mm(s.depthMm)), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = s.depthMm,
        onValueChange = { edit(s.copy(depthMm = (it * 100).roundToInt() / 100f)) },
        valueRange = 0f..TextureLayerSettings.MAX_DEPTH_MM,
        enabled = enabled,
    )
    // Tile size on a log scale: half a millimetre to 20 cm, fine where it matters.
    Text(stringResource(R.string.prepare_texture_tile, mm(s.tileMm)), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = ln(s.tileMm.coerceIn(TextureLayerSettings.MIN_TILE_MM, TextureLayerSettings.MAX_TILE_MM)),
        onValueChange = { edit(s.copy(tileMm = (exp(it) * 10).roundToInt() / 10f)) },
        valueRange = ln(TextureLayerSettings.MIN_TILE_MM)..ln(TextureLayerSettings.MAX_TILE_MM),
        enabled = enabled,
    )
    Text(stringResource(R.string.prepare_texture_rotation, s.rotationDegrees.roundToInt()), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = s.rotationDegrees.coerceIn(-180f, 180f),
        onValueChange = { edit(s.copy(rotationDegrees = it.roundToInt().toFloat())) },
        valueRange = -180f..180f,
        enabled = enabled,
    )
    SwitchRow(stringResource(R.string.prepare_texture_invert), s.invert, enabled) { edit(s.copy(invert = it)) }

    var more by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { more = !more }) {
        Text(stringResource(if (more) R.string.prepare_texture_less else R.string.prepare_texture_more))
    }
    if (!more) return

    Text(stringResource(R.string.prepare_texture_mapping), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (projection in TextureProjection.entries) {
            if (projection == TextureProjection.MODEL_UV && !texture.stack.hasModelUv && s.projection != projection) continue
            FilterChip(
                selected = s.projection == projection,
                onClick = { edit(s.copy(projection = projection)) },
                enabled = enabled,
                label = { Text(stringResource(projectionLabel(projection))) },
            )
        }
    }
    // The bottom layer has nothing beneath it to combine with.
    if (texture.stack.layers.firstOrNull()?.slot != layer.slot) {
        Text(stringResource(R.string.prepare_texture_blend), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (blend in TextureBlend.entries) {
                FilterChip(
                    selected = s.blend == blend,
                    onClick = { edit(s.copy(blend = blend)) },
                    enabled = enabled,
                    label = { Text(stringResource(blendLabel(blend))) },
                )
            }
        }
    }
    Text(stringResource(R.string.prepare_texture_midlevel, (s.midlevel * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium)
    Slider(value = s.midlevel, onValueChange = { edit(s.copy(midlevel = (it * 100).roundToInt() / 100f)) }, enabled = enabled)
    Text(stringResource(R.string.prepare_texture_midlevel_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.prepare_texture_smoothing, (s.smoothing * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium)
    Slider(value = s.smoothing, onValueChange = { edit(s.copy(smoothing = (it * 100).roundToInt() / 100f)) }, enabled = enabled)
    SwitchRow(stringResource(R.string.prepare_texture_fade), s.fadeEdge, enabled) { edit(s.copy(fadeEdge = it)) }
    if (s.fadeEdge) {
        Text(stringResource(R.string.prepare_texture_fade_amount, (s.edgeFade * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium)
        Slider(value = s.edgeFade, onValueChange = { edit(s.copy(edgeFade = (it * 100).roundToInt().coerceAtLeast(1) / 100f)) }, enabled = enabled)
    }
    SwitchRow(stringResource(R.string.prepare_texture_tiled), s.tiled, enabled) { edit(s.copy(tiled = it)) }
    if (s.tiled) SwitchRow(stringResource(R.string.prepare_texture_mirror), s.mirrorTiles, enabled) { edit(s.copy(mirrorTiles = it)) }
    val half = s.tileMm / 2
    Text(stringResource(R.string.prepare_texture_offset_x, mm(s.offsetXMm)), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = s.offsetXMm.coerceIn(-half, half),
        onValueChange = { edit(s.copy(offsetXMm = (it * 10).roundToInt() / 10f)) },
        valueRange = -half..half,
        enabled = enabled,
    )
    Text(stringResource(R.string.prepare_texture_offset_y, mm(s.offsetYMm)), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = s.offsetYMm.coerceIn(-half, half),
        onValueChange = { edit(s.copy(offsetYMm = (it * 10).roundToInt() / 10f)) },
        valueRange = -half..half,
        enabled = enabled,
    )
    if (texture.stack.layers.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onIntent(TextureIntent.Move(layer.slot, 1)) }, enabled = enabled && texture.stack.layers.lastOrNull()?.slot != layer.slot) {
                Text(stringResource(R.string.prepare_texture_move_up))
            }
            TextButton(onClick = { onIntent(TextureIntent.Move(layer.slot, -1)) }, enabled = enabled && texture.stack.layers.firstOrNull()?.slot != layer.slot) {
                Text(stringResource(R.string.prepare_texture_move_down))
            }
        }
    }

    // What applies to the whole stack.
    val stack = texture.stack
    SwitchRow(stringResource(R.string.prepare_texture_border), stack.displaceBorder, enabled) {
        onIntent(TextureIntent.SetOptions(stack.smooth, stack.smoothStrength, stack.smoothPasses, it))
    }
    SwitchRow(stringResource(R.string.prepare_texture_smooth), stack.smooth, enabled) {
        onIntent(TextureIntent.SetOptions(it, stack.smoothStrength, stack.smoothPasses, stack.displaceBorder))
    }
    if (stack.smooth) {
        var strength by rememberSaveable(stack.smoothStrength) { mutableStateOf(stack.smoothStrength) }
        Text(stringResource(R.string.prepare_texture_smooth_strength, (strength * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = strength,
            onValueChange = { strength = (it * 100).roundToInt() / 100f },
            onValueChangeFinished = { onIntent(TextureIntent.SetOptions(true, strength, stack.smoothPasses, stack.displaceBorder)) },
            enabled = enabled,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TexturePicker(
    texture: TextureUiState,
    onPick: (com.orcamobile.core.model.LibraryTexture) -> Unit,
    onPickFile: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.prepare_texture_pick_title), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (entry in texture.library) {
                    Column(
                        Modifier.width(72.dp).clip(MaterialTheme.shapes.small).clickable(enabled = !texture.busy) { onPick(entry) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        TextureThumbnail(entry.path, 64.dp)
                        Text(entry.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TonalPillButton(stringResource(R.string.prepare_texture_from_file), onClick = onPickFile, enabled = !texture.busy, modifier = Modifier.weight(1f))
                TonalPillButton(stringResource(R.string.prepare_texture_cancel), onClick = onCancel, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A texture image, small: decoded off the main thread at about [size], kept while shown. */
@Composable
private fun TextureThumbnail(path: String, size: Dp) {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, path, px) {
        value = withContext(Dispatchers.IO) { thumbnail(path, px) }
    }
    Surface(
        modifier = Modifier.size(size),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop) } ?: Box(Modifier.size(size))
    }
}

private fun thumbnail(path: String, px: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

private fun mm(value: Float): String = String.format(Locale.ROOT, if (value < 10) "%.2f" else "%.1f", value)

private fun count(triangles: Long): String = when {
    triangles >= 1_000_000 -> String.format(Locale.ROOT, "%.1f M", triangles / 1_000_000.0)
    triangles >= 1_000 -> "${(triangles + 500) / 1000} k"
    else -> triangles.toString()
}

private fun projectionLabel(p: TextureProjection): Int = when (p) {
    TextureProjection.TRIPLANAR -> R.string.prepare_texture_projection_triplanar
    TextureProjection.CYLINDRICAL -> R.string.prepare_texture_projection_cylindrical
    TextureProjection.SPHERICAL -> R.string.prepare_texture_projection_spherical
    TextureProjection.UNWRAP -> R.string.prepare_texture_projection_unwrap
    TextureProjection.MODEL_UV -> R.string.prepare_texture_projection_model
}

private fun blendLabel(b: TextureBlend): Int = when (b) {
    TextureBlend.ADD -> R.string.prepare_texture_blend_add
    TextureBlend.SUBTRACT -> R.string.prepare_texture_blend_subtract
    TextureBlend.MULTIPLY -> R.string.prepare_texture_blend_multiply
    TextureBlend.DIVIDE -> R.string.prepare_texture_blend_divide
    TextureBlend.OVERRIDE -> R.string.prepare_texture_blend_override
}
