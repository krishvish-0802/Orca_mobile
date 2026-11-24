package com.orcamobile.feature.prepare

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.ChoiceSheet
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.model.PresetChoices
import com.orcamobile.core.model.PresetKind

/**
 * The filaments loaded, as the desktop sidebar's filament list: one chip per
 * filament with its number and colour; tap one to change its preset or colour,
 * or take it away. "+" adds a filament, a copy of the last.
 */
@Composable
internal fun FilamentBar(choices: PresetChoices, onIntent: (PrepareIntent) -> Unit, modifier: Modifier = Modifier) {
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    val colours = choices.slotColoursOrDefault()
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        choices.slots.forEachIndexed { index, slot ->
            Surface(
                onClick = { editing = index },
                enabled = choices.filaments.isNotEmpty(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    Modifier.widthIn(max = 220.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilamentSwatch(colours.getOrElse(index) { Color.Gray }, index + 1)
                    Column {
                        Text(
                            stringResource(R.string.prepare_filament_number, index + 1),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(slot.preset, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (choices.slots.isEmpty()) {
            Text(stringResource(R.string.prepare_preset_none), style = MaterialTheme.typography.bodyMedium)
        }
        AssistChip(
            onClick = { onIntent(PrepareIntent.AddFilament) },
            enabled = choices.slots.isNotEmpty() && choices.slots.size < PresetChoices.MAX_FILAMENTS,
            label = { Text(stringResource(R.string.prepare_filament_add)) },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp)) },
        )
    }
    editing?.let { index ->
        if (index in choices.slots.indices) {
            FilamentSheet(choices, index, onIntent, onDismiss = { editing = null })
        } else {
            editing = null
        }
    }
}

/** A filament's colour with its number on it, readable on light and dark colours. */
@Composable
internal fun FilamentSwatch(colour: Color, number: Int, size: Int = 28) {
    Box(
        Modifier
            .size(size.dp)
            .background(colour, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (colour.luminance() > 0.5f) Color.Black else Color.White,
        )
    }
}

/** One filament: its preset, its colour (its own or the preset's), and taking it away. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilamentSheet(choices: PresetChoices, index: Int, onIntent: (PrepareIntent) -> Unit, onDismiss: () -> Unit) {
    var choosingPreset by rememberSaveable { mutableStateOf(false) }
    val slot = choices.slots[index]
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.prepare_filament_number, index + 1), style = MaterialTheme.typography.titleLarge)
            Surface(
                onClick = { choosingPreset = true },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.prepare_preset_filament),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(slot.preset, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(stringResource(R.string.prepare_filament_colour), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FILAMENT_SWATCHES.forEach { hex ->
                    val colour = parseFilamentColour(hex) ?: Color.Gray
                    val chosen = slot.colour.equals(hex, ignoreCase = true)
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(colour, CircleShape)
                            .border(if (chosen) 3.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { onIntent(PrepareIntent.SetFilamentColour(index, hex)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (chosen) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = if (colour.luminance() > 0.5f) Color.Black else Color.White,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TonalPillButton(
                    stringResource(R.string.prepare_filament_preset_colour),
                    onClick = { onIntent(PrepareIntent.SetFilamentColour(index, null)) },
                    enabled = slot.colour != null,
                    modifier = Modifier.weight(1f),
                )
                TonalPillButton(
                    stringResource(R.string.prepare_filament_remove),
                    onClick = {
                        onDismiss()
                        onIntent(PrepareIntent.RemoveFilament(index))
                    },
                    enabled = choices.slots.size > 1,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(R.string.prepare_filament_remove_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryPillButton(stringResource(R.string.prepare_plate_done), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
    if (choosingPreset) {
        ChoiceSheet(
            title = stringResource(R.string.prepare_filament_number, index + 1),
            options = choices.filaments,
            selected = slot.preset,
            onSelect = { onIntent(PrepareIntent.SelectFilament(it, index)) },
            onDismiss = { choosingPreset = false },
            searchHint = stringResource(R.string.prepare_search),
            marked = choices.userNames(PresetKind.FILAMENT),
            markLabel = stringResource(R.string.prepare_preset_yours),
        )
    }
}

/** Colours to pick from: Bambu's basic PLA range, which most filament colours sit near. */
private val FILAMENT_SWATCHES = listOf(
    "#FFFFFF", "#161616", "#8E9089", "#D1D3D5", "#F72323", "#FF6A13", "#FEC600", "#F4EE2A",
    "#00AE42", "#164B35", "#0A2989", "#0086D6", "#00B1B7", "#5E43B7", "#E8AFCF", "#AE96D4",
    "#9D2235", "#7D6556", "#C8A064", "#F5E6C8",
)
