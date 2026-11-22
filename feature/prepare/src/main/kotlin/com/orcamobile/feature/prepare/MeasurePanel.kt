package com.orcamobile.feature.prepare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.NumberField
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.model.AssemblyStep
import com.orcamobile.core.model.FeatureKind
import com.orcamobile.core.model.MeasuredFeature
import java.util.Locale
import kotlin.math.sqrt

/**
 * The desktop's Measure and Assembly: tap a feature (point, edge, circle or
 * face) on a part, then another, to see the distance and angle between them;
 * in an assembly mode, bring the second part to the first by its face or point.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MeasurePanel(measure: MeasureUiState, onIntent: (MeasureIntent) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.prepare_measure_title), style = MaterialTheme.typography.titleMedium)
            val modes = MeasureMode.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = measure.mode == mode,
                        onClick = { onIntent(MeasureIntent.SetMode(mode)) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        icon = {},
                    ) { Text(stringResource(modeLabel(mode)), maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                }
            }
            Text(
                stringResource(modeHint(measure.mode, measure.picks.size)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val outcome = measure.outcome
            outcome?.features?.forEachIndexed { index, feature ->
                Text(
                    stringResource(R.string.prepare_measure_feature, index + 1, describe(feature)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (index == 0) FIRST_COLOR else SECOND_COLOR,
                )
            }
            outcome?.measurement?.let { m ->
                HorizontalDivider()
                val distance = m.distanceMm
                distance?.let { ResultRow(stringResource(R.string.prepare_measure_distance), mm(it)) }
                m.distanceInfiniteMm?.takeIf { d -> distance == null || kotlin.math.abs(d - distance) > 1e-3 }?.let {
                    ResultRow(stringResource(R.string.prepare_measure_distance_infinite), mm(it))
                }
                m.angleDegrees?.let { ResultRow(stringResource(R.string.prepare_measure_angle), String.format(Locale.ROOT, "%.2f°", it)) }
                m.xyzMm?.takeIf { it.size == 3 }?.let { (x, y, z) ->
                    ResultRow(stringResource(R.string.prepare_measure_xyz), String.format(Locale.ROOT, "%.2f, %.2f, %.2f mm", x, y, z))
                }
            }

            val options = outcome?.assembly
            if (outcome != null && options != null && measure.picks.size == 2) {
                if (!options.differentParts) {
                    if (measure.mode != MeasureMode.MEASURE) {
                        Text(
                            stringResource(R.string.prepare_measure_same_part),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    AssemblySection(measure, onIntent)
                }
            }
            measure.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TonalPillButton(
                    stringResource(R.string.prepare_measure_clear),
                    onClick = { onIntent(MeasureIntent.Clear) },
                    enabled = measure.picks.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
                PrimaryPillButton(stringResource(R.string.prepare_measure_done), onClick = { onIntent(MeasureIntent.Close) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssemblySection(measure: MeasureUiState, onIntent: (MeasureIntent) -> Unit) {
    val options = measure.outcome?.assembly ?: return
    val enabled = !measure.busy
    HorizontalDivider()
    Text(
        stringResource(if (options.sameObject) R.string.prepare_assembly_moves_part else R.string.prepare_assembly_moves_object),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (options.centerCoincidence) {
            PrimaryPillButton(stringResource(R.string.prepare_assembly_center), onClick = { onIntent(MeasureIntent.Assemble(AssemblyStep.CENTER_COINCIDENCE)) }, enabled = enabled)
        }
        if (options.parallel) {
            TonalPillButton(stringResource(R.string.prepare_assembly_parallel), onClick = { onIntent(MeasureIntent.Assemble(AssemblyStep.PARALLEL)) }, enabled = enabled)
        }
        if (options.flipFirst) {
            TonalPillButton(stringResource(R.string.prepare_assembly_flip_first), onClick = { onIntent(MeasureIntent.Assemble(AssemblyStep.FLIP_FIRST)) }, enabled = enabled)
        }
        if (options.flipSecond) {
            TonalPillButton(stringResource(R.string.prepare_assembly_flip_second), onClick = { onIntent(MeasureIntent.Assemble(AssemblyStep.FLIP_SECOND)) }, enabled = enabled)
        }
    }
    if (options.hasParallelDistance) {
        NumberField(
            value = options.parallelDistanceMm,
            onCommit = { onIntent(MeasureIntent.Assemble(AssemblyStep.PARALLEL_DISTANCE, value = it)) },
            label = stringResource(R.string.prepare_assembly_parallel_distance),
            suffix = "mm",
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (options.rotateAround) {
        var degrees by rememberSaveable { mutableDoubleStateOf(90.0) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField(degrees, { degrees = it }, stringResource(R.string.prepare_assembly_rotate_by), Modifier.weight(1f), "°", enabled = enabled)
            TonalPillButton(
                stringResource(R.string.prepare_assembly_rotate),
                onClick = { onIntent(MeasureIntent.Assemble(AssemblyStep.ROTATE_AROUND, value = degrees)) },
                enabled = enabled && degrees != 0.0,
                modifier = Modifier.weight(1f),
            )
        }
    }
    val xyz = measure.outcome?.measurement?.xyzMm
    if (options.setXyz && xyz != null && xyz.size == 3) {
        Text(stringResource(R.string.prepare_assembly_xyz), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (axis in 0..2) {
                NumberField(
                    value = xyz[axis],
                    onCommit = { v -> onIntent(MeasureIntent.Assemble(AssemblyStep.SET_XYZ, xyz = xyz.toMutableList().also { it[axis] = v })) },
                    label = listOf("X", "Y", "Z")[axis],
                    suffix = "mm",
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        TonalPillButton(
            stringResource(R.string.prepare_assembly_coincide),
            onClick = { onIntent(MeasureIntent.Assemble(AssemblyStep.SET_XYZ, xyz = listOf(0.0, 0.0, 0.0))) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun mm(value: Double) = String.format(Locale.ROOT, "%.2f mm", value)

@Composable
private fun describe(feature: MeasuredFeature): String = when (feature.kind) {
    FeatureKind.POINT -> stringResource(R.string.prepare_measure_point)
    FeatureKind.EDGE -> {
        val (a, b) = feature.a to feature.b
        val length = sqrt((0..2).sumOf { (b[it] - a[it]) * (b[it] - a[it]) })
        stringResource(R.string.prepare_measure_edge, mm(length))
    }
    FeatureKind.CIRCLE -> stringResource(R.string.prepare_measure_circle, mm(2 * feature.radius))
    FeatureKind.PLANE -> stringResource(R.string.prepare_measure_plane)
}

private fun modeLabel(mode: MeasureMode) = when (mode) {
    MeasureMode.MEASURE -> R.string.prepare_measure_mode_measure
    MeasureMode.ASSEMBLE_FACES -> R.string.prepare_measure_mode_faces
    MeasureMode.ASSEMBLE_POINTS -> R.string.prepare_measure_mode_points
}

private fun modeHint(mode: MeasureMode, picks: Int) = when {
    picks == 0 && mode == MeasureMode.MEASURE -> R.string.prepare_measure_hint_first
    picks == 0 && mode == MeasureMode.ASSEMBLE_FACES -> R.string.prepare_assembly_hint_faces
    picks == 0 -> R.string.prepare_assembly_hint_points
    picks == 1 -> R.string.prepare_measure_hint_second
    else -> R.string.prepare_measure_hint_again
}

private val FIRST_COLOR = androidx.compose.ui.graphics.Color(0xFF2F9FE0)
private val SECOND_COLOR = androidx.compose.ui.graphics.Color(0xFFE04B4B)
