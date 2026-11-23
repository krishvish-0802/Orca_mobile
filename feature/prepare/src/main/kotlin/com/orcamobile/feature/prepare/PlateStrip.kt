package com.orcamobile.feature.prepare

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.designsystem.icon.OrcaIcons
import com.orcamobile.core.model.PlateSettingKeys

/**
 * The project's plates under the plate view, as the desktop's plate tabs: tap
 * one to show, edit and slice it; the gear opens the current plate's settings.
 * Each chip says how many instances the plate holds, and ticks plates whose
 * slice still matches them.
 */
@Composable
internal fun PlateStrip(state: PrepareUiState, onIntent: (PrepareIntent) -> Unit, modifier: Modifier = Modifier) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val enabled = state.canSwitchPlate
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.plates.forEachIndexed { index, plate ->
            val current = index == state.currentPlate
            FilterChip(
                selected = current,
                onClick = { if (current) settingsOpen = true else onIntent(PrepareIntent.SelectPlate(index)) },
                enabled = enabled || current,
                label = { Text(stringResource(R.string.prepare_plate_chip, plate.label(index), state.instancesOn(index))) },
                leadingIcon = if (index in state.slicedPlates) {
                    { Icon(Icons.Filled.Check, stringResource(R.string.prepare_plate_sliced), Modifier.size(18.dp)) }
                } else {
                    null
                },
                trailingIcon = if (current) {
                    { Icon(Icons.Filled.Settings, stringResource(R.string.prepare_plate_settings), Modifier.size(18.dp)) }
                } else {
                    null
                },
            )
        }
        AssistChip(
            onClick = { onIntent(PrepareIntent.AddPlate) },
            enabled = enabled,
            label = { Text(stringResource(R.string.prepare_plate_add)) },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp)) },
        )
    }
    if (settingsOpen) {
        PlateSettingsSheet(state, onIntent, onDismiss = { settingsOpen = false })
    }
}

/**
 * The current plate's own settings, as the desktop's plate settings dialog:
 * its name, bed type, print sequence and spiral vase, each either following the
 * settings or set for this plate; and deleting the plate.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PlateSettingsSheet(state: PrepareUiState, onIntent: (PrepareIntent) -> Unit, onDismiss: () -> Unit) {
    val index = state.currentPlate
    val plate = state.plates.getOrNull(index) ?: return
    val editable = state.canSwitchPlate
    var name by remember(index) { mutableStateOf(plate.name) }
    var confirmRemove by remember { mutableStateOf(false) }
    val commitName = { if (name.trim() != plate.name) onIntent(PrepareIntent.RenamePlate(index, name)) }
    val set = { key: String, value: String? -> onIntent(PrepareIntent.SetPlateSetting(index, key, value)) }

    ModalBottomSheet(
        onDismissRequest = {
            commitName()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.prepare_plate_settings_title, plate.label(index)), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.prepare_plate_name)) },
                placeholder = { Text(stringResource(R.string.prepare_plate_name_hint, index + 1)) },
                singleLine = true,
                enabled = editable,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commitName() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.prepare_plate_settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val same = stringResource(R.string.prepare_plate_same_as_global)
            ChoiceRow(
                title = stringResource(R.string.prepare_plate_bed_type),
                choices = listOf(null to same) + PlateSettingKeys.bedTypes,
                selected = plate.settings[PlateSettingKeys.BED_TYPE],
                enabled = editable,
            ) { set(PlateSettingKeys.BED_TYPE, it) }
            ChoiceRow(
                title = stringResource(R.string.prepare_plate_print_sequence),
                choices = listOf(
                    null to same,
                    PlateSettingKeys.BY_LAYER to stringResource(R.string.prepare_plate_by_layer),
                    PlateSettingKeys.BY_OBJECT to stringResource(R.string.prepare_plate_by_object),
                ),
                selected = plate.settings[PlateSettingKeys.PRINT_SEQUENCE],
                enabled = editable,
            ) { set(PlateSettingKeys.PRINT_SEQUENCE, it) }
            ChoiceRow(
                title = stringResource(R.string.prepare_plate_spiral),
                choices = listOf(
                    null to same,
                    "1" to stringResource(R.string.prepare_plate_on),
                    "0" to stringResource(R.string.prepare_plate_off),
                ),
                selected = plate.settings[PlateSettingKeys.SPIRAL_VASE],
                enabled = editable,
            ) { set(PlateSettingKeys.SPIRAL_VASE, it) }

            TonalPillButton(
                stringResource(R.string.prepare_arrange_all),
                onClick = {
                    commitName()
                    onDismiss()
                    onIntent(PrepareIntent.ArrangeAllPlates)
                },
                icon = OrcaIcons.Arrange,
                enabled = state.plateEditable,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TonalPillButton(
                    stringResource(R.string.prepare_plate_remove),
                    onClick = {
                        if (state.instancesOn(index) > 0) {
                            confirmRemove = true
                        } else {
                            onDismiss()
                            onIntent(PrepareIntent.RemovePlate(index))
                        }
                    },
                    enabled = editable && state.plates.size > 1,
                    modifier = Modifier.weight(1f),
                )
                PrimaryPillButton(
                    stringResource(R.string.prepare_plate_done),
                    onClick = {
                        commitName()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (confirmRemove) {
        val count = state.instancesOn(index)
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.prepare_plate_remove_title, plate.label(index))) },
            text = { Text(pluralStringResource(R.plurals.prepare_plate_remove_text, count, count)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onDismiss()
                    onIntent(PrepareIntent.RemovePlate(index))
                }) { Text(stringResource(R.string.prepare_plate_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.prepare_plate_cancel)) }
            },
        )
    }
}

/** One plate setting: "same as settings" (null) or one of its values. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(
    title: String,
    choices: List<Pair<String?, String>>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (value, label) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { if (value != selected) onSelect(value) },
                    enabled = enabled,
                    label = { Text(label) },
                )
            }
        }
    }
}

/** Where the selected object goes: another plate, or a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoveToPlateSheet(state: PrepareUiState, onMove: (Int?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.prepare_to_plate_title, state.selectedObjectName.orEmpty()),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            state.plates.forEachIndexed { index, plate ->
                val here = index == state.currentPlate
                ListItem(
                    headlineContent = {
                        Text(if (here) stringResource(R.string.prepare_to_plate_current, plate.label(index)) else plate.label(index))
                    },
                    supportingContent = { Text(state.instancesOn(index).toString()) },
                    leadingContent = { Icon(OrcaIcons.ToPlate, contentDescription = null) },
                    modifier = Modifier.clickable(enabled = !here) { onMove(index) }.padding(horizontal = 8.dp),
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.prepare_to_plate_new)) },
                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                modifier = Modifier.clickable { onMove(null) }.padding(horizontal = 8.dp),
            )
        }
    }
}
