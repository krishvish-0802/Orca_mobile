package com.orcamobile.feature.prepare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.NumberField
import com.orcamobile.core.model.Axes
import com.orcamobile.core.model.Axis
import com.orcamobile.core.model.InstanceTransform
import com.orcamobile.core.model.TransformEdit

/**
 * The selected object's exact placement, as the desktop's object manipulation
 * panel: position on the bed, rotation, scale and size (one axis or all three),
 * mirroring and a reset. Drags and twists on the plate show up here too.
 */
@Composable
internal fun ObjectPanel(state: PrepareUiState, onIntent: (PrepareIntent) -> Unit) {
    val transform = state.transform ?: return
    val enabled = state.plateEditable
    val edit: (TransformEdit) -> Unit = { onIntent(PrepareIntent.EditTransform(it)) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.selectedObjectName ?: stringResource(R.string.prepare_object_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                TextButton(onClick = { edit(TransformEdit.Reset) }, enabled = enabled) {
                    Text(stringResource(R.string.prepare_object_reset))
                }
            }

            Section(stringResource(R.string.prepare_object_position)) {
                AxisField(Axis.X, transform.position.x, "mm", enabled) { edit(TransformEdit.Position(it, transform.position.y)) }
                AxisField(Axis.Y, transform.position.y, "mm", enabled) { edit(TransformEdit.Position(transform.position.x, it)) }
            }
            Section(stringResource(R.string.prepare_object_rotation)) {
                for (axis in Axis.entries) {
                    AxisField(axis, transform.rotationDegrees[axis], "°", enabled) {
                        edit(TransformEdit.Rotation(transform.rotationDegrees.with(axis, it)))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.prepare_object_scale), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.prepare_object_uniform), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.uniformScale,
                    onCheckedChange = { onIntent(PrepareIntent.SetUniformScale(it)) },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (axis in Axis.entries) {
                    AxisField(axis, transform.scale[axis] * 100, "%", enabled) { percent ->
                        scaled(transform, axis, percent / 100 / transform.scale[axis], state.uniformScale)?.let(edit)
                    }
                }
            }
            Section(stringResource(R.string.prepare_object_size)) {
                for (axis in Axis.entries) {
                    AxisField(axis, transform.size[axis], "mm", enabled) { size ->
                        val current = transform.size[axis]
                        if (current > 0) scaled(transform, axis, size / current, state.uniformScale)?.let(edit)
                    }
                }
            }
            Section(stringResource(R.string.prepare_object_mirror)) {
                for (axis in Axis.entries) {
                    FilterChip(
                        selected = axis in transform.mirrored,
                        onClick = { edit(TransformEdit.Mirror(axis)) },
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
private fun Section(title: String, content: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun RowScope.AxisField(
    axis: Axis,
    value: Double,
    unit: String,
    enabled: Boolean,
    onCommit: (Double) -> Unit,
) {
    NumberField(
        value = value,
        onCommit = onCommit,
        label = axis.name,
        suffix = unit,
        enabled = enabled,
        modifier = Modifier.weight(1f),
    )
}
