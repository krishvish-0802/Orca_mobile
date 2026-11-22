package com.orcamobile.feature.prepare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.model.SimplifyDetail
import com.orcamobile.core.model.SimplifySettings
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * The desktop's Simplify for the selected part, or every part of the selected
 * object: to a detail level or by a share of the triangles. The plate shows
 * the result as a wireframe until it is applied.
 */
@Composable
internal fun SimplifyPanel(
    state: PrepareUiState,
    partName: String?,
    busy: Boolean,
    onIntent: (PrepareIntent) -> Unit,
    onApply: (SimplifySettings) -> Unit,
) {
    val settings = state.simplify
    val set: (SimplifySettings) -> Unit = { onIntent(PrepareIntent.SetSimplify(it)) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.prepare_simplify_title, partName ?: state.selectedObjectName.orEmpty()),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            val modes = listOf(R.string.prepare_simplify_by_detail, R.string.prepare_simplify_by_ratio)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = settings.byRatio == (index == 1),
                        onClick = { set(settings.copy(byRatio = index == 1)) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        icon = {},
                    ) { Text(stringResource(label)) }
                }
            }
            if (settings.byRatio) {
                Text(stringResource(R.string.prepare_simplify_ratio, settings.ratioPercent.roundToInt()), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.ratioPercent.toFloat(),
                    onValueChange = { set(settings.copy(ratioPercent = it.roundToInt().toDouble())) },
                    valueRange = 1f..99f,
                )
            } else {
                Text(
                    stringResource(R.string.prepare_simplify_detail, stringResource(detailLabel(settings.detail))),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = settings.detail.nativeValue.toFloat(),
                    onValueChange = { value ->
                        set(settings.copy(detail = SimplifyDetail.entries.first { it.nativeValue == value.roundToInt() }))
                    },
                    valueRange = 0f..4f,
                    steps = 3,
                )
            }
            val counts = state.simplifyCounts
            Text(
                if (counts == null) {
                    stringResource(R.string.prepare_simplify_counting)
                } else {
                    val format = NumberFormat.getIntegerInstance()
                    stringResource(R.string.prepare_simplify_counts, format.format(counts.before), format.format(counts.after))
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.prepare_simplify_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TonalPillButton(
                    stringResource(R.string.prepare_cut_cancel),
                    onClick = { onIntent(PrepareIntent.CloseMeshTool) },
                    modifier = Modifier.weight(1f),
                )
                PrimaryPillButton(
                    stringResource(R.string.prepare_simplify_apply),
                    onClick = { onApply(settings) },
                    enabled = !busy && counts != null && counts.after < counts.before,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun detailLabel(detail: SimplifyDetail) = when (detail) {
    SimplifyDetail.EXTRA_HIGH -> R.string.prepare_simplify_extra_high
    SimplifyDetail.HIGH -> R.string.prepare_simplify_high
    SimplifyDetail.MEDIUM -> R.string.prepare_simplify_medium
    SimplifyDetail.LOW -> R.string.prepare_simplify_low
    SimplifyDetail.EXTRA_LOW -> R.string.prepare_simplify_extra_low
}
