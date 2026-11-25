package com.orcamobile.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.R

/**
 * Asked before a preset change leaves changed settings behind, as the
 * desktop's unsaved-changes dialog: keep them over the [to] preset, discard
 * them, or save them as the user's preset first. [canSave] is false where
 * there is nothing of the [from] preset's own to save.
 */
@Composable
fun UnsavedChangesDialog(
    changes: Int,
    from: String,
    to: String,
    canSave: Boolean,
    error: String?,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var naming by rememberSaveable { mutableStateOf(error != null) }
    val suggested = stringResource(R.string.ds_unsaved_copy_name, from)
    var name by rememberSaveable { mutableStateOf(suggested) }
    if (naming) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.ds_unsaved_save_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = error != null,
                    label = { Text(stringResource(R.string.ds_unsaved_name)) },
                    supportingText = error?.let { { Text(it) } },
                )
            },
            confirmButton = {
                TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) {
                    Text(stringResource(R.string.ds_unsaved_save_and_switch))
                }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.ds_unsaved_back)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ds_unsaved_title)) },
        text = { Text(pluralStringResource(R.plurals.ds_unsaved_body, changes, changes, from, to), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                TextButton(onClick = onKeep) { Text(stringResource(R.string.ds_unsaved_keep)) }
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.ds_unsaved_discard)) }
                if (canSave) TextButton(onClick = { naming = true }) { Text(stringResource(R.string.ds_unsaved_save)) }
            }
        },
    )
}
