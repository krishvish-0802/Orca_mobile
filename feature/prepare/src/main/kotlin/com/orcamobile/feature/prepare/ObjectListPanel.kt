package com.orcamobile.feature.prepare

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.NumberField
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.model.PrimitiveShape
import com.orcamobile.core.model.VolumeKind

/** The object's volumes: pick one to highlight it and place it; add, retype, rename or delete them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PartsTab(
    list: ObjectListUiState,
    plateEditable: Boolean,
    uniformScale: Boolean,
    onIntent: (PrepareIntent) -> Unit,
    onListIntent: (ObjectListIntent) -> Unit,
    onPickFile: (VolumeKind) -> Unit,
) {
    val details = list.details ?: return
    val enabled = plateEditable && !list.busy
    var adding by rememberSaveable { mutableStateOf<VolumeKind?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        details.volumes.forEachIndexed { index, volume ->
            val selected = list.selectedVolume == index
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        MaterialTheme.shapes.medium,
                    )
                    .clickable { onListIntent(ObjectListIntent.SelectVolume(if (selected) null else index)) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).background(kindColor(volume.kind), CircleShape))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(volume.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(stringResource(kindLabel(volume.kind)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val lastPart = volume.kind == VolumeKind.PART && details.partCount == 1
                IconButton(onClick = { onListIntent(ObjectListIntent.RemoveVolume(index)) }, enabled = enabled && !lastPart) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.prepare_parts_delete, volume.name))
                }
            }
        }

        list.volume?.let { volume ->
            val index = list.selectedVolume ?: return@let
            HorizontalDivider()
            NameField(volume.name, enabled) { onListIntent(ObjectListIntent.Rename(index, it)) }
            Text(stringResource(R.string.prepare_parts_kind), style = MaterialTheme.typography.labelLarge)
            val lastPart = volume.kind == VolumeKind.PART && details.partCount == 1
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (kind in ObjectListViewModel.kindsFor(volume.kind)) {
                    FilterChip(
                        selected = volume.kind == kind,
                        onClick = { if (kind != volume.kind) onListIntent(ObjectListIntent.SetKind(index, kind)) },
                        enabled = enabled && (!lastPart || kind == VolumeKind.PART),
                        label = { Text(stringResource(kindLabel(kind))) },
                    )
                }
            }
            TransformFields(
                transform = volume.transform,
                enabled = enabled,
                uniformScale = uniformScale,
                onUniformScale = { onIntent(PrepareIntent.SetUniformScale(it)) },
                showZ = true,
                showMirror = false,
                onEdit = { onListIntent(ObjectListIntent.EditVolume(it)) },
            )
        }

        Text(stringResource(R.string.prepare_parts_add), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (kind in VolumeKind.addable) {
                AssistChip(
                    onClick = { adding = kind },
                    enabled = enabled,
                    label = { Text(stringResource(kindLabel(kind))) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
    adding?.let { kind ->
        ShapeSheet(
            kind = kind,
            onShape = { shape ->
                adding = null
                onListIntent(ObjectListIntent.AddShape(kind, shape))
            },
            onFile = {
                adding = null
                onPickFile(kind)
            },
            onDismiss = { adding = null },
        )
    }
}

/** Height ranges with their own layer height, as the desktop's height range modifier. */
@Composable
internal fun HeightsTab(list: ObjectListUiState, plateEditable: Boolean, onListIntent: (ObjectListIntent) -> Unit) {
    val details = list.details ?: return
    val enabled = plateEditable && !list.busy
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.prepare_heights_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        details.layerRanges.forEachIndexed { index, range ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(
                    value = range.minZ,
                    onCommit = { onListIntent(ObjectListIntent.SetRange(index, it, range.maxZ)) },
                    label = stringResource(R.string.prepare_heights_from),
                    suffix = "mm",
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    value = range.maxZ,
                    onCommit = { onListIntent(ObjectListIntent.SetRange(index, range.minZ, it)) },
                    label = stringResource(R.string.prepare_heights_to),
                    suffix = "mm",
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    value = range.layerHeight()?.toDoubleOrNull() ?: 0.0,
                    onCommit = { if (it > 0) onListIntent(ObjectListIntent.SetRangeLayerHeight(index, it)) },
                    label = stringResource(R.string.prepare_heights_layer),
                    suffix = "mm",
                    decimals = 3,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onListIntent(ObjectListIntent.RemoveRange(index)) }, enabled = enabled) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.prepare_heights_delete))
                }
            }
        }
        TonalPillButton(
            stringResource(R.string.prepare_heights_add),
            onClick = { onListIntent(ObjectListIntent.AddRange) },
            icon = Icons.Filled.Add,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NameField(name: String, enabled: Boolean, onRename: (String) -> Unit) {
    var text by remember(name) { mutableStateOf(name) }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.prepare_parts_name)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            if (text.isNotBlank() && text != name) onRename(text.trim()) else text = name
            focus.clearFocus()
        }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ShapeSheet(kind: VolumeKind, onShape: (PrimitiveShape) -> Unit, onFile: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.prepare_parts_add_title, stringResource(kindLabel(kind))), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (shape in PrimitiveShape.entries) {
                    AssistChip(onClick = { onShape(shape) }, label = { Text(stringResource(shapeLabel(shape))) })
                }
            }
            PrimaryPillButton(stringResource(R.string.prepare_parts_from_file), onClick = onFile, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.prepare_parts_add_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.prepare_parts_cancel)) }
        }
    }
}

/** The plate's colors for each kind (native/viewport/src/Viewport.cpp), opaque for the list. */
private fun kindColor(kind: VolumeKind): Color = when (kind) {
    VolumeKind.PART -> Color(0xFF008C8C)
    VolumeKind.NEGATIVE_VOLUME -> Color(0xFF404047)
    VolumeKind.MODIFIER, VolumeKind.PAINTED_MODIFIER -> Color(0xFFF2CC33)
    VolumeKind.SUPPORT_ENFORCER -> Color(0xFF33CC4D)
    VolumeKind.SUPPORT_BLOCKER -> Color(0xFFE64040)
    else -> Color(0xFFA659E6)
}

internal fun kindLabel(kind: VolumeKind): Int = when (kind) {
    VolumeKind.PART -> R.string.prepare_kind_part
    VolumeKind.NEGATIVE_VOLUME -> R.string.prepare_kind_negative
    VolumeKind.MODIFIER -> R.string.prepare_kind_modifier
    VolumeKind.SUPPORT_BLOCKER -> R.string.prepare_kind_support_blocker
    VolumeKind.SUPPORT_ENFORCER -> R.string.prepare_kind_support_enforcer
    VolumeKind.SEAM_CENTER -> R.string.prepare_kind_seam_center
    VolumeKind.SEAM_LEFT -> R.string.prepare_kind_seam_left
    VolumeKind.SEAM_RIGHT -> R.string.prepare_kind_seam_right
    VolumeKind.SEAM_ENFORCED -> R.string.prepare_kind_seam_enforced
    VolumeKind.SEAM_BLOCKED -> R.string.prepare_kind_seam_blocked
    VolumeKind.SEAM_NEUTRAL -> R.string.prepare_kind_seam_neutral
    VolumeKind.PAINTED_MODIFIER -> R.string.prepare_kind_painted_modifier
}

private fun shapeLabel(shape: PrimitiveShape): Int = when (shape) {
    PrimitiveShape.CUBE -> R.string.prepare_shape_cube
    PrimitiveShape.CYLINDER -> R.string.prepare_shape_cylinder
    PrimitiveShape.SPHERE -> R.string.prepare_shape_sphere
    PrimitiveShape.SLAB -> R.string.prepare_shape_slab
    PrimitiveShape.CONE -> R.string.prepare_shape_cone
    PrimitiveShape.TORUS -> R.string.prepare_shape_torus
    PrimitiveShape.DISC -> R.string.prepare_shape_disc
}
