package com.orcamobile.feature.prepare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.NumberField
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.TonalPillButton
import kotlin.math.roundToInt

/**
 * The desktop's Brim ears for the selected object: tap near its outline on the
 * bed to add an ear, tap an ear to pick it (resize or remove it), or find ears
 * at its sharp corners. They print only with the "painted" brim type.
 */
@Composable
internal fun BrimEarsPanel(state: BrimEarsUiState, objectName: String, onIntent: (BrimEarsIntent) -> Unit, onDone: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.prepare_ears_title, objectName), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (state.switchedToPainted) {
                Text(
                    stringResource(R.string.prepare_ears_switched),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.brimPainted == false) {
                Text(
                    stringResource(R.string.prepare_ears_not_painted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TonalPillButton(
                    stringResource(R.string.prepare_ears_use_painted),
                    onClick = { onIntent(BrimEarsIntent.UsePaintedBrim) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                pluralStringResource(R.plurals.prepare_ears_count, state.ears.size, state.ears.size),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(if (state.missed) R.string.prepare_ears_missed else R.string.prepare_ears_hint),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    if (state.selected != null) R.string.prepare_ears_radius_selected else R.string.prepare_ears_radius,
                    String.format(java.util.Locale.ROOT, "%.1f", state.radiusMm),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = state.radiusMm.toFloat().coerceIn(0.5f, 20f),
                onValueChange = { onIntent(BrimEarsIntent.SetRadius((it * 10).roundToInt() / 10.0)) },
                valueRange = 0.5f..20f,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TonalPillButton(
                    stringResource(R.string.prepare_ears_remove),
                    onClick = { onIntent(BrimEarsIntent.RemoveSelected) },
                    enabled = state.selected != null && !state.busy,
                    modifier = Modifier.weight(1f),
                )
                TonalPillButton(
                    stringResource(R.string.prepare_cut_remove_all),
                    onClick = { onIntent(BrimEarsIntent.RemoveAll) },
                    enabled = state.ears.isNotEmpty() && !state.busy,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider()
            Text(stringResource(R.string.prepare_ears_auto), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    state.maxAngleDegrees,
                    { onIntent(BrimEarsIntent.SetDetection(it, state.detectionMm)) },
                    stringResource(R.string.prepare_ears_max_angle),
                    Modifier.weight(1f),
                    "°",
                    decimals = 0,
                )
                NumberField(
                    state.detectionMm,
                    { onIntent(BrimEarsIntent.SetDetection(state.maxAngleDegrees, it)) },
                    stringResource(R.string.prepare_ears_detection),
                    Modifier.weight(1f),
                    "mm",
                    decimals = 1,
                )
            }
            TonalPillButton(
                stringResource(R.string.prepare_ears_auto_generate),
                onClick = { onIntent(BrimEarsIntent.AutoGenerate) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            PrimaryPillButton(stringResource(R.string.prepare_measure_done), onClick = onDone, modifier = Modifier.fillMaxWidth())
        }
    }
}
