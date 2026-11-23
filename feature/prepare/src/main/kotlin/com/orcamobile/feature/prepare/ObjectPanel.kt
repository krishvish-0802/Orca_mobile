package com.orcamobile.feature.prepare

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.NumberField
import com.orcamobile.core.model.Axes
import com.orcamobile.core.model.Axis
import com.orcamobile.core.model.InstanceTransform
import com.orcamobile.core.model.TransformEdit
import com.orcamobile.core.model.VolumeKind
import com.orcamobile.core.viewport.HandleMode

private enum class ObjectTab { PLACEMENT, PARTS, HEIGHTS }

/**
 * The selected object, as the desktop's object manipulation panel and object
 * list: its placement on the bed; its parts, modifiers and other volumes; and
 * its height ranges. Drags and twists on the plate show up here too.
 */
@Composable
internal fun ObjectPanel(
    state: PrepareUiState,
    list: ObjectListUiState,
    onIntent: (PrepareIntent) -> Unit,
    onListIntent: (ObjectListIntent) -> Unit,
    onPickFile: (VolumeKind) -> Unit,
    onOpenSettings: (volume: Int?, range: Int?) -> Unit,
) {
    val transform = state.transform ?: return
    var tab by rememberSaveable { mutableStateOf(ObjectTab.PLACEMENT) }
    // A volume picked on the plate opens the Parts tab.
    LaunchedEffect(list.selectedVolume) { if (list.selectedVolume != null) tab = ObjectTab.PARTS }
    val enabled = state.plateEditable
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.selectedObjectName ?: stringResource(R.string.prepare_object_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (tab == ObjectTab.PLACEMENT) {
                    TextButton(onClick = { onIntent(PrepareIntent.EditTransform(TransformEdit.Reset)) }, enabled = enabled) {
                        Text(stringResource(R.string.prepare_object_reset))
                    }
                }
                val changed = list.details?.settings?.userChanges() ?: 0
                TextButton(onClick = { onOpenSettings(null, null) }, enabled = enabled && list.details != null) {
                    Text(
                        if (changed > 0) stringResource(R.string.prepare_object_settings_changed, changed)
                        else stringResource(R.string.prepare_object_settings),
                    )
                }
            }
            val details = list.details
            // Several filaments: which one the object, and the part picked, print with.
            val filaments = state.choices.slots.size
            if (details != null && filaments > 1) {
                val colours = state.choices.slotColoursOrDefault()
                FilamentChoice(
                    label = stringResource(R.string.prepare_object_filament),
                    selected = details.settings[EXTRUDER_KEY]?.toIntOrNull()?.takeIf { it in 1..filaments } ?: 1,
                    colours = colours,
                    enabled = enabled && !list.busy,
                    allowDefault = false,
                ) { onListIntent(ObjectListIntent.SetFilament(null, it)) }
                val volumeIndex = list.selectedVolume
                val volume = list.volume
                if (volumeIndex != null && volume != null && volume.kind == VolumeKind.PART && details.volumes.size > 1) {
                    FilamentChoice(
                        label = volume.name,
                        selected = volume.settings[EXTRUDER_KEY]?.toIntOrNull()?.takeIf { it in 1..filaments },
                        colours = colours,
                        enabled = enabled && !list.busy,
                        allowDefault = true,
                    ) { onListIntent(ObjectListIntent.SetFilament(volumeIndex, it)) }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ObjectTab.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = tab == entry,
                        onClick = {
                            tab = entry
                            if (entry != ObjectTab.PARTS) onListIntent(ObjectListIntent.SelectVolume(null))
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, ObjectTab.entries.size),
                        icon = {},
                    ) {
                        Text(
                            when (entry) {
                                ObjectTab.PLACEMENT -> stringResource(R.string.prepare_object_tab_placement)
                                ObjectTab.PARTS -> stringResource(R.string.prepare_object_tab_parts, details?.volumes?.size ?: 1)
                                ObjectTab.HEIGHTS -> stringResource(R.string.prepare_object_tab_heights, details?.layerRanges?.size ?: 0)
                            },
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            when (tab) {
                ObjectTab.PLACEMENT -> TransformFields(
                    transform = transform,
                    enabled = enabled,
                    uniformScale = state.uniformScale,
                    onUniformScale = { onIntent(PrepareIntent.SetUniformScale(it)) },
                    showZ = false,
                    showMirror = true,
                    onEdit = { onIntent(PrepareIntent.EditTransform(it)) },
                    onHandleMode = { onIntent(PrepareIntent.SetHandleMode(it)) },
                )
                ObjectTab.PARTS -> PartsTab(list, enabled, state.uniformScale, onIntent, onListIntent, onPickFile) { volume ->
                    onOpenSettings(volume, null)
                }
                ObjectTab.HEIGHTS -> HeightsTab(list, enabled, onListIntent) { range -> onOpenSettings(null, range) }
            }
            list.error?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onListIntent(ObjectListIntent.DismissError) }) { Text(stringResource(R.string.prepare_object_dismiss)) }
                }
            }
        }
    }
}

/**
 * Position, rotation, scale (one axis or all three) and size, and optionally
 * mirroring: an object on the bed (no height, it stands on the bed) or a volume
 * inside its object ([showZ]).
 */
@Composable
internal fun TransformFields(
    transform: InstanceTransform,
    enabled: Boolean,
    uniformScale: Boolean,
    onUniformScale: (Boolean) -> Unit,
    showZ: Boolean,
    showMirror: Boolean,
    onEdit: (TransformEdit) -> Unit,
    onHandleMode: (HandleMode) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(stringResource(R.string.prepare_object_position), onTitle = { onHandleMode(HandleMode.MOVE) }) {
            val p = transform.position
            AxisField(Axis.X, p.x, "mm", enabled) { onEdit(TransformEdit.Position(it, p.y, p.z.takeIf { showZ })) }
            AxisField(Axis.Y, p.y, "mm", enabled) { onEdit(TransformEdit.Position(p.x, it, p.z.takeIf { showZ })) }
            if (showZ) AxisField(Axis.Z, p.z, "mm", enabled) { onEdit(TransformEdit.Position(p.x, p.y, it)) }
        }
        Section(stringResource(R.string.prepare_object_rotation), onTitle = { onHandleMode(HandleMode.ROTATE) }) {
            for (axis in Axis.entries) {
                AxisField(axis, transform.rotationDegrees[axis], "°", enabled) {
                    onEdit(TransformEdit.Rotation(transform.rotationDegrees.with(axis, it)))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.prepare_object_scale),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).clickable { onHandleMode(HandleMode.SCALE) },
            )
            Text(stringResource(R.string.prepare_object_uniform), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = uniformScale, onCheckedChange = onUniformScale, modifier = Modifier.padding(start = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (axis in Axis.entries) {
                AxisField(axis, transform.scale[axis] * 100, "%", enabled) { percent ->
                    scaled(transform, axis, percent / 100 / transform.scale[axis], uniformScale)?.let(onEdit)
                }
            }
        }
        Section(stringResource(R.string.prepare_object_size), onTitle = { onHandleMode(HandleMode.SCALE) }) {
            for (axis in Axis.entries) {
                AxisField(axis, transform.size[axis], "mm", enabled) { size ->
                    val current = transform.size[axis]
                    if (current > 0) scaled(transform, axis, size / current, uniformScale)?.let(onEdit)
                }
            }
        }
        if (showMirror) {
            Section(stringResource(R.string.prepare_object_mirror)) {
                for (axis in Axis.entries) {
                    FilterChip(
                        selected = axis in transform.mirrored,
                        onClick = { onEdit(TransformEdit.Mirror(axis)) },
                        enabled = enabled,
                        label = { Text(axis.name) },
                    )
                }
            }
        }
    }
}

/** The scale after growing [axis] (or all three when [uniform]) by [ratio]; null when it would not be positive. */
private fun scaled(transform: InstanceTransform, axis: Axis, ratio: Double, uniform: Boolean): TransformEdit.Scale? {
    if (!ratio.isFinite() || ratio <= 0) return null
    val factors: Axes = if (uniform) transform.scale.map { it * ratio } else transform.scale.with(axis, transform.scale[axis] * ratio)
    return TransformEdit.Scale(factors)
}

@Composable
internal fun Section(title: String, onTitle: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (onTitle == null) {
            Text(title, style = MaterialTheme.typography.labelLarge)
        } else {
            // Tapping a heading shows its handles on the plate.
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onTitle),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun RowScope.AxisField(axis: Axis, value: Double, unit: String, enabled: Boolean, onCommit: (Double) -> Unit) {
    NumberField(
        value = value,
        onCommit = onCommit,
        label = axis.name,
        suffix = unit,
        enabled = enabled,
        modifier = Modifier.weight(1f),
    )
}

private const val EXTRUDER_KEY = "extruder"

/**
 * The filament something prints with, as the desktop object list's filament
 * column: a swatch per filament; with [allowDefault], "As the object" (null).
 */
@Composable
private fun FilamentChoice(
    label: String,
    selected: Int?,
    colours: List<androidx.compose.ui.graphics.Color>,
    enabled: Boolean,
    allowDefault: Boolean,
    onSelect: (Int?) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.widthIn(max = 96.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (allowDefault) {
                FilterChip(
                    selected = selected == null,
                    onClick = { onSelect(null) },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.prepare_object_filament_default)) },
                )
            }
            colours.forEachIndexed { i, colour ->
                val number = i + 1
                Box(
                    Modifier
                        .border(
                            if (selected == number) 3.dp else 0.dp,
                            if (selected == number) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                            androidx.compose.foundation.shape.CircleShape,
                        )
                        .padding(3.dp)
                        .clickable(enabled = enabled && selected != number) { onSelect(number) },
                ) { FilamentSwatch(colour, number, size = 30) }
            }
        }
    }
}
