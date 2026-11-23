package com.orcamobile.feature.prepare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.model.LayerProfile
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The desktop's Variable layer height for the selected object: the layer
 * heights up the object as a graph to brush (drag right for thicker layers,
 * left for thinner, around the height touched), Adaptive, Smooth and Reset.
 */
@Composable
internal fun LayerHeightPanel(state: LayerHeightUiState, objectName: String, onIntent: (LayerHeightIntent) -> Unit, onDone: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.prepare_layers_title, objectName), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            val profile = state.profile
            if (profile == null) {
                Text(stringResource(R.string.prepare_layers_loading), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    stringResource(
                        R.string.prepare_layers_summary,
                        profile.layerCount,
                        fmt(profile.minLayerHeight),
                        fmt(profile.maxLayerHeight),
                    ) + if (profile.custom) "" else " · " + stringResource(R.string.prepare_layers_uniform),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LayerGraph(profile, onBrush = { z, delta -> onIntent(LayerHeightIntent.Brush(z, delta)) })
                Text(
                    stringResource(R.string.prepare_layers_brush_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !state.smoothBrush,
                        onClick = { onIntent(LayerHeightIntent.SetSmoothBrush(false)) },
                        label = { Text(stringResource(R.string.prepare_layers_brush_thickness)) },
                    )
                    FilterChip(
                        selected = state.smoothBrush,
                        onClick = { onIntent(LayerHeightIntent.SetSmoothBrush(true)) },
                        label = { Text(stringResource(R.string.prepare_layers_brush_smooth)) },
                    )
                }
                Text(stringResource(R.string.prepare_layers_band, fmt(state.bandMm)), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = state.bandMm.toFloat(),
                    onValueChange = { onIntent(LayerHeightIntent.SetBand((it * 2).roundToInt() / 2.0)) },
                    valueRange = 0.5f..20f,
                )
            }
            HorizontalDivider()
            Text(stringResource(R.string.prepare_layers_quality, fmt(state.quality)), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = state.quality.toFloat(),
                onValueChange = { onIntent(LayerHeightIntent.SetQuality((it * 20).roundToInt() / 20.0)) },
                valueRange = 0f..1f,
            )
            PrimaryPillButton(
                stringResource(R.string.prepare_layers_adaptive),
                onClick = { onIntent(LayerHeightIntent.Adaptive) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.prepare_layers_radius, state.smoothRadius), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = state.smoothRadius.toFloat(),
                onValueChange = { onIntent(LayerHeightIntent.SetSmoothing(it.roundToInt(), state.keepMin)) },
                valueRange = 1f..10f,
                steps = 8,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.prepare_layers_keep_min), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = state.keepMin, onCheckedChange = { onIntent(LayerHeightIntent.SetSmoothing(state.smoothRadius, it)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TonalPillButton(
                    stringResource(R.string.prepare_layers_smooth),
                    onClick = { onIntent(LayerHeightIntent.Smooth) },
                    enabled = !state.busy && profile != null,
                    modifier = Modifier.weight(1f),
                )
                TonalPillButton(
                    stringResource(R.string.prepare_layers_reset),
                    onClick = { onIntent(LayerHeightIntent.Reset) },
                    enabled = !state.busy && profile?.custom == true,
                    modifier = Modifier.weight(1f),
                )
            }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            PrimaryPillButton(stringResource(R.string.prepare_measure_done), onClick = onDone, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Layer height (across) up the object (bottom to top), between the slice's
 * smallest and largest layer heights; the slice's own layer height dashed.
 * A drag across brushes at the height of the finger: [onBrush] gets that height
 * (mm above the object's base) and how much thicker (negative: thinner), mm.
 */
@Composable
private fun LayerGraph(profile: LayerProfile, onBrush: (z: Double, delta: Double) -> Unit) {
    val line = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val guide = MaterialTheme.colorScheme.outline
    val touch = MaterialTheme.colorScheme.tertiary
    val currentProfile by rememberUpdatedState(profile)
    val currentOnBrush by rememberUpdatedState(onBrush)
    var touchY by remember { mutableStateOf<Float?>(null) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { touchY = it.y },
                    onDragEnd = { touchY = null },
                    onDragCancel = { touchY = null },
                ) { change, drag ->
                    change.consume()
                    touchY = change.position.y
                    val p = currentProfile
                    val span = (p.maxLayerHeight - p.minLayerHeight).takeIf { it > 0 } ?: return@detectDragGestures
                    val z = ((1f - change.position.y / size.height).coerceIn(0f, 1f) * p.objectHeight).toDouble()
                    // A drag across the whole graph spans the slice's range of layer heights.
                    currentOnBrush(z, drag.x / size.width * span)
                }
            },
    ) {
        val p = profile
        if (p.objectHeight <= 0 || p.maxLayerHeight <= p.minLayerHeight) return@Canvas
        fun x(h: Double) = ((h - p.minLayerHeight) / (p.maxLayerHeight - p.minLayerHeight)).toFloat().coerceIn(0f, 1f) * size.width
        fun y(z: Double) = size.height * (1f - (z / p.objectHeight).toFloat().coerceIn(0f, 1f))
        // The layers themselves, as the slice will make them.
        val layers = Path()
        layers.moveTo(0f, size.height)
        var i = 0
        while (i + 1 < p.layers.size) {
            val bottom = p.layers[i]
            val top = p.layers[i + 1]
            val h = top - bottom
            layers.lineTo(x(h), y(bottom))
            layers.lineTo(x(h), y(top))
            i += 2
        }
        layers.lineTo(0f, 0f)
        layers.close()
        drawPath(layers, fill)
        // The profile.
        val curve = Path()
        var k = 0
        while (k + 1 < p.profile.size) {
            val px = x(p.profile[k + 1])
            val py = y(p.profile[k])
            if (k == 0) curve.moveTo(px, py) else curve.lineTo(px, py)
            k += 2
        }
        drawPath(curve, line, style = Stroke(width = 2.dp.toPx()))
        val dx = x(p.layerHeight)
        drawLine(guide, Offset(dx, 0f), Offset(dx, size.height), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
        touchY?.let { drawLine(touch, Offset(0f, it), Offset(size.width, it), strokeWidth = 2.dp.toPx()) }
    }
}

private fun fmt(value: Double) = String.format(Locale.ROOT, "%.2f", value)
