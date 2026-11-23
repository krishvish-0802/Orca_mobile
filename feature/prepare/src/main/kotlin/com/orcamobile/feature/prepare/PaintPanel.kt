package com.orcamobile.feature.prepare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.model.PaintBrush
import com.orcamobile.core.model.PaintKind
import com.orcamobile.core.model.PaintTool
import java.util.Locale

/**
 * The desktop's painting tools for the selected object: what to paint (support
 * or seam enforcers and blockers, fuzzy skin, filament colours), with what
 * (brush, ball, smart fill, fill) and how big.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaintPanel(
    state: PrepareUiState,
    onIntent: (PrepareIntent) -> Unit,
    list: ObjectListUiState = ObjectListUiState(),
    onListIntent: (ObjectListIntent) -> Unit = {},
    /** A painted modifier's settings (its volume index). */
    onOpenSettings: (Int) -> Unit = {},
    fuzzySkinDisabled: Boolean = false,
    onEnableFuzzySkin: () -> Unit = {},
) {
    val brush = state.paint ?: return
    val set: (PaintBrush) -> Unit = { onIntent(PrepareIntent.SetBrush(it)) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.prepare_paint_title, state.selectedObjectName.orEmpty()),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            val kinds = state.paintKinds
            // The object's painted modifiers, by volume index: the last one is painted on when switching to them.
            val modifiers = list.details?.volumes.orEmpty().withIndex()
                .filter { it.value.kind == com.orcamobile.core.model.VolumeKind.PAINTED_MODIFIER }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (kind in kinds) {
                    FilterChip(
                        selected = brush.kind == kind,
                        onClick = {
                            if (kind != brush.kind) {
                                val modifier = modifiers.lastOrNull()?.index.takeIf { kind == PaintKind.PAINTED_MODIFIER }
                                set(brush.copy(kind = kind, state = PaintBrush.ENFORCE, modifier = modifier))
                            }
                        },
                        label = { Text(stringResource(kindLabel(kind)), maxLines = 1) },
                    )
                }
            }
            if (brush.kind == PaintKind.PAINTED_MODIFIER) {
                PaintedModifierSection(brush, modifiers.map { it.index to it.value }, list.busy, set, onListIntent, onOpenSettings)
            }

            Text(stringResource(R.string.prepare_paint_with), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, label) in statesFor(brush.kind, state)) {
                    FilterChip(
                        selected = brush.state == value,
                        onClick = { set(brush.copy(state = value)) },
                        label = { Text(label) },
                        leadingIcon = if (value == PaintBrush.ERASE) null else {
                            { ColorDot(paletteFor(brush.kind, state.choices.slotColoursOrDefault()).getOrElse(value) { Color.Gray }) }
                        },
                    )
                }
            }

            Text(stringResource(R.string.prepare_paint_tool), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (tool in PaintTool.entries) {
                    FilterChip(
                        selected = brush.tool == tool,
                        onClick = { set(brush.copy(tool = tool)) },
                        label = { Text(stringResource(toolLabel(tool))) },
                    )
                }
            }

            if (brush.tool == PaintTool.CIRCLE || brush.tool == PaintTool.SPHERE) {
                Text(
                    stringResource(R.string.prepare_paint_size, String.format(Locale.ROOT, "%.1f", brush.radiusMm * 2)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = brush.radiusMm,
                    onValueChange = { set(brush.copy(radiusMm = (it * 10).toInt() / 10f)) },
                    valueRange = PaintBrush.MIN_RADIUS_MM..PaintBrush.MAX_RADIUS_MM,
                )
            } else {
                Text(
                    stringResource(R.string.prepare_paint_fill_angle, brush.fillAngleDegrees.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = brush.fillAngleDegrees,
                    onValueChange = { set(brush.copy(fillAngleDegrees = it.toInt().toFloat())) },
                    valueRange = 0f..90f,
                )
            }

            if (brush.kind == PaintKind.SUPPORTS) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.prepare_paint_overhangs), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = brush.overhangAngleDegrees != null,
                        onCheckedChange = { on -> set(brush.copy(overhangAngleDegrees = if (on) DEFAULT_OVERHANG_DEGREES else null)) },
                    )
                }
                brush.overhangAngleDegrees?.let { angle ->
                    Text(stringResource(R.string.prepare_paint_overhang_angle, angle.toInt()), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = angle,
                        onValueChange = { set(brush.copy(overhangAngleDegrees = it.toInt().toFloat())) },
                        valueRange = 0f..90f,
                    )
                }
            }

            if (brush.kind == PaintKind.FUZZY_SKIN && fuzzySkinDisabled) {
                Text(
                    stringResource(R.string.prepare_paint_fuzzy_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TonalPillButton(
                    stringResource(R.string.prepare_paint_fuzzy_enable),
                    onClick = onEnableFuzzySkin,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                stringResource(hintFor(brush.kind)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TonalPillButton(
                    stringResource(R.string.prepare_paint_clear),
                    onClick = { onIntent(PrepareIntent.ClearPaint) },
                    modifier = Modifier.weight(1f),
                )
                PrimaryPillButton(
                    stringResource(R.string.prepare_paint_done),
                    onClick = { onIntent(PrepareIntent.StopPainting) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ColorDot(color: Color) {
    Surface(
        modifier = Modifier.padding(2.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = color,
    ) { androidx.compose.foundation.layout.Box(Modifier.padding(6.dp)) }
}

private const val DEFAULT_OVERHANG_DEGREES = 30f

/** What the brush can paint for [kind], with its label: erase first. */
@Composable
private fun statesFor(kind: PaintKind, state: PrepareUiState): List<Pair<Int, String>> = when (kind) {
    PaintKind.SUPPORTS, PaintKind.SEAM -> listOf(
        PaintBrush.ENFORCE to stringResource(R.string.prepare_paint_enforce),
        PaintBrush.BLOCK to stringResource(R.string.prepare_paint_block),
        PaintBrush.ERASE to stringResource(R.string.prepare_paint_erase),
    )
    PaintKind.FUZZY_SKIN -> listOf(
        1 to stringResource(R.string.prepare_paint_fuzzy),
        PaintBrush.ERASE to stringResource(R.string.prepare_paint_erase),
    )
    PaintKind.PAINTED_MODIFIER -> listOf(
        PaintBrush.ENFORCE to stringResource(R.string.prepare_paint_modifier_area),
        PaintBrush.ERASE to stringResource(R.string.prepare_paint_erase),
    )
    PaintKind.COLOR -> (1..(state.presets?.filaments?.size ?: 1)).map { it to stringResource(R.string.prepare_paint_filament, it) } +
        (PaintBrush.ERASE to stringResource(R.string.prepare_paint_erase))
}

/**
 * Colours by painted state (0 is unpainted), as the desktop draws them; colour
 * painting draws each filament in its own colour ([filaments], filament 1 first).
 */
internal fun paletteFor(kind: PaintKind, filaments: List<Color> = emptyList()): List<Color> = when (kind) {
    PaintKind.SUPPORTS -> listOf(Color.Transparent, Color(0xFF33CC4D), Color(0xFFE64040))
    PaintKind.SEAM -> listOf(Color.Transparent, Color(0xFF3380FF), Color(0xFFE64040))
    PaintKind.FUZZY_SKIN -> listOf(Color.Transparent, Color(0xFFFF9919))
    PaintKind.COLOR -> listOf(Color.Transparent) + filaments + FILAMENT_COLORS.drop(filaments.size)
    PaintKind.PAINTED_MODIFIER -> listOf(Color.Transparent, Color(0xFFF2CC33))
}

/** The loaded filaments' colours, filament 1 first; a slot not yet known takes the stock colour. */
internal fun com.orcamobile.core.model.PresetChoices.slotColoursOrDefault(): List<Color> =
    slots.indices.map { i -> colourOf(i)?.let(::parseFilamentColour) ?: FILAMENT_COLORS[i % FILAMENT_COLORS.size] }

/** "#RRGGBB" or "#RRGGBBAA" as profiles write it; null for anything else. */
internal fun parseFilamentColour(hex: String): Color? {
    val digits = hex.removePrefix("#")
    if (digits.length != 6 && digits.length != 8) return null
    val value = digits.toLongOrNull(16) ?: return null
    return if (digits.length == 6) Color(0xFF000000 or value) else Color(((value and 0xFF) shl 24) or (value ushr 8))
}

private val FILAMENT_COLORS = listOf(
    Color(0xFF00AE42), Color(0xFFFFFFFF), Color(0xFF161616), Color(0xFFF72323), Color(0xFF0A2989), Color(0xFFFEC600),
    Color(0xFF8E9089), Color(0xFFE8AFCF), Color(0xFF00B1B7), Color(0xFFA3D8E1), Color(0xFF9D2235), Color(0xFF7D6556),
)

private fun kindLabel(kind: PaintKind): Int = when (kind) {
    PaintKind.SUPPORTS -> R.string.prepare_paint_kind_supports
    PaintKind.SEAM -> R.string.prepare_paint_kind_seam
    PaintKind.FUZZY_SKIN -> R.string.prepare_paint_kind_fuzzy
    PaintKind.COLOR -> R.string.prepare_paint_kind_color
    PaintKind.PAINTED_MODIFIER -> R.string.prepare_paint_kind_modifier
}

/**
 * The desktop's painted modifier tool: which modifier the strokes shape (or a
 * new one), how deep it reaches into the part, and its settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaintedModifierSection(
    brush: PaintBrush,
    modifiers: List<Pair<Int, com.orcamobile.core.model.VolumeInfo>>,
    busy: Boolean,
    set: (PaintBrush) -> Unit,
    onListIntent: (ObjectListIntent) -> Unit,
    onOpenSettings: (Int) -> Unit,
) {
    Text(stringResource(R.string.prepare_paint_modifier_which), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((index, volume) in modifiers) {
            FilterChip(
                selected = brush.modifier == index,
                onClick = { set(brush.copy(modifier = index)) },
                label = { Text(volume.name, maxLines = 1) },
            )
        }
        FilterChip(
            selected = brush.modifier == null,
            onClick = { set(brush.copy(modifier = null)) },
            label = { Text(stringResource(R.string.prepare_paint_modifier_new)) },
        )
    }
    val current = modifiers.firstOrNull { it.first == brush.modifier }
    if (current == null) {
        Text(
            stringResource(R.string.prepare_paint_modifier_new_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val (index, volume) = current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        com.orcamobile.core.designsystem.component.NumberField(
            volume.paintedDepthMm,
            { onListIntent(ObjectListIntent.SetPaintedDepth(index, it)) },
            stringResource(R.string.prepare_paint_modifier_depth),
            Modifier.weight(1f),
            "mm",
        )
        val changed = volume.settings.userChanges()
        TonalPillButton(
            if (changed > 0) stringResource(R.string.prepare_parts_settings_changed, changed)
            else stringResource(R.string.prepare_parts_settings),
            onClick = { onOpenSettings(index) },
            enabled = !busy,
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        stringResource(R.string.prepare_paint_modifier_depth_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun toolLabel(tool: PaintTool): Int = when (tool) {
    PaintTool.CIRCLE -> R.string.prepare_paint_tool_brush
    PaintTool.SPHERE -> R.string.prepare_paint_tool_ball
    PaintTool.SMART_FILL -> R.string.prepare_paint_tool_smart_fill
    PaintTool.BUCKET_FILL -> R.string.prepare_paint_tool_fill
}

private fun hintFor(kind: PaintKind): Int = when (kind) {
    PaintKind.SUPPORTS -> R.string.prepare_paint_hint_supports
    PaintKind.SEAM -> R.string.prepare_paint_hint_seam
    PaintKind.FUZZY_SKIN -> R.string.prepare_paint_hint_fuzzy
    PaintKind.COLOR -> R.string.prepare_paint_hint_color
    PaintKind.PAINTED_MODIFIER -> R.string.prepare_paint_hint_modifier
}
