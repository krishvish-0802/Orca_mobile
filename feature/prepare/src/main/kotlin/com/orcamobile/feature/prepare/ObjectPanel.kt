package com.orcamobile.feature.prepare

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
) {
    val transform = state.transform ?: return
    var tab by rememberSaveable { mutableStateOf(ObjectTab.PLACEMENT) }
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
            }
            val details = list.details
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
                )
                ObjectTab.PARTS -> PartsTab(list, enabled, state.uniformScale, onIntent, onListIntent, onPickFile)
                ObjectTab.HEIGHTS -> HeightsTab(list, enabled, onListIntent)
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(stringResource(R.string.prepare_object_position)) {
            val p = transform.position
            AxisField(Axis.X, p.x, "mm", enabled) { onEdit(TransformEdit.Position(it, p.y, p.z.takeIf { showZ })) }
            AxisField(Axis.Y, p.y, "mm", enabled) { onEdit(TransformEdit.Position(p.x, it, p.z.takeIf { showZ })) }
            if (showZ) AxisField(Axis.Z, p.z, "mm", enabled) { onEdit(TransformEdit.Position(p.x, p.y, it)) }
        }
        Section(stringResource(R.string.prepare_object_rotation)) {
            for (axis in Axis.entries) {
                AxisField(axis, transform.rotationDegrees[axis], "°", enabled) {
                    onEdit(TransformEdit.Rotation(transform.rotationDegrees.with(axis, it)))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.prepare_object_scale), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
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
        Section(stringResource(R.string.prepare_object_size)) {
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
internal fun Section(title: String, content: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
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
