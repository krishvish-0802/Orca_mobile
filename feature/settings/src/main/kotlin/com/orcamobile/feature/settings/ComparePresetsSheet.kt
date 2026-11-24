package com.orcamobile.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.ChoiceSheet

/** Two presets side by side, as the desktop's Compare presets: only the settings they set differently. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComparePresetsSheet(comparison: ComparisonUi, onIntent: (SettingsIntent) -> Unit) {
    var picking by remember { mutableStateOf<Int?>(null) }
    ModalBottomSheet(
        onDismissRequest = { onIntent(SettingsIntent.CloseComparison) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_compare_title), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetButton(comparison.first, Modifier.weight(1f)) { picking = 0 }
                PresetButton(comparison.second, Modifier.weight(1f)) { picking = 1 }
            }
            when {
                comparison.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                comparison.error != null -> Text(comparison.error, color = MaterialTheme.colorScheme.error)
                comparison.first == null || comparison.second == null ->
                    Text(stringResource(R.string.settings_compare_pick), color = MaterialTheme.colorScheme.onSurfaceVariant)
                comparison.differences.isEmpty() ->
                    Text(stringResource(R.string.settings_compare_same), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(comparison.differences, key = { it.option.key }) { difference ->
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(difference.option.label, style = MaterialTheme.typography.bodyLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Value(SettingValues.display(difference.option, difference.first), Modifier.weight(1f))
                                    Value(SettingValues.display(difference.option, difference.second), Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    picking?.let { side ->
        ChoiceSheet(
            title = stringResource(R.string.settings_compare_choose),
            options = comparison.choices,
            selected = if (side == 0) comparison.first else comparison.second,
            onSelect = { name ->
                onIntent(
                    if (side == 0) SettingsIntent.Compare(name, comparison.second) else SettingsIntent.Compare(comparison.first, name),
                )
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun PresetButton(name: String?, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text(name ?: stringResource(R.string.settings_compare_choose), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Value(text: String, modifier: Modifier) {
    Text(
        text.ifEmpty { "—" },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
