package com.orcamobile.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.ListGroup
import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.UserPreset

/**
 * Saving the changes as the user's own presets (one per kind changed), and
 * renaming or deleting the presets saved before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetsSheet(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf<UserPreset?>(null) }
    var deleting by remember { mutableStateOf<UserPreset?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.saveTargets.isNotEmpty()) {
                Text(stringResource(R.string.settings_presets_save_title), style = MaterialTheme.typography.titleMedium)
                state.saveTargets.forEach { target -> SaveTargetRow(target, state.userPresets, onIntent) }
            }
            Text(stringResource(R.string.settings_compare_title), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetKind.entries.forEach { kind ->
                    OutlinedButton(onClick = { onIntent(SettingsIntent.OpenComparison(kind)) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(kindName(kind)), maxLines = 1)
                    }
                }
            }
            Text(stringResource(R.string.settings_presets_mine), style = MaterialTheme.typography.titleMedium)
            if (state.userPresets.isEmpty()) {
                Text(stringResource(R.string.settings_presets_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ListGroup(items = state.userPresets) { preset ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(kindName(preset.kind)) + " · " + stringResource(R.string.settings_presets_based_on, preset.inherits),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { renaming = preset }) { Text(stringResource(R.string.settings_presets_rename)) }
                        TextButton(onClick = { deleting = preset }) { Text(stringResource(R.string.settings_presets_delete)) }
                    }
                }
            }
        }
    }
    renaming?.let { preset ->
        NameDialog(
            title = stringResource(R.string.settings_presets_rename_title),
            initial = preset.name,
            taken = state.userPresets.filter { it.kind == preset.kind && it != preset }.map { it.name },
            onConfirm = { name ->
                onIntent(SettingsIntent.RenamePreset(preset.kind, preset.name, name))
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.settings_presets_delete_title, preset.name)) },
            text = { Text(stringResource(R.string.settings_presets_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onIntent(SettingsIntent.DeletePreset(preset.kind, preset.name))
                    deleting = null
                }) { Text(stringResource(R.string.settings_presets_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

/** One kind's changes: a name to save them under (the user's own preset's, to overwrite it) and Save. */
@Composable
private fun SaveTargetRow(target: SaveTarget, mine: List<UserPreset>, onIntent: (SettingsIntent) -> Unit) {
    val suggested = if (target.currentIsUsers) target.current else stringResource(R.string.settings_presets_copy_name, target.current)
    var name by remember(target.kind, target.current) { mutableStateOf(suggested) }
    val trimmed = name.trim()
    val overwrites = mine.any { it.kind == target.kind && it.name == trimmed }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(kindName(target.kind)) + " · " +
                pluralStringResource(R.plurals.settings_presets_changes, target.changes, target.changes),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_presets_name)) },
                supportingText = if (overwrites) {
                    { Text(stringResource(R.string.settings_presets_overwrites)) }
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onIntent(SettingsIntent.SavePreset(target.kind, trimmed)) }, enabled = trimmed.isNotEmpty()) {
                Text(stringResource(R.string.settings_presets_save))
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, taken: List<String>, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val trimmed = name.trim()
    val clash = trimmed in taken
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                isError = clash,
                supportingText = if (clash) {
                    { Text(stringResource(R.string.settings_presets_taken)) }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty() && !clash && trimmed != initial) {
                Text(stringResource(R.string.settings_presets_rename))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

private fun kindName(kind: PresetKind) = when (kind) {
    PresetKind.PROCESS -> R.string.settings_tab_process
    PresetKind.FILAMENT -> R.string.settings_tab_filament
    PresetKind.PRINTER -> R.string.settings_tab_printer
}
