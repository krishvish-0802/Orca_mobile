package com.orcamobile.feature.prepare

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.designsystem.icon.OrcaIcons
import com.orcamobile.core.model.ArrangeOptions
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** How long a plate notice stays over the plate. */
private const val NOTICE_MILLIS = 4_000L

/** The most copies the copies sheet offers at once. */
private const val MAX_COPIES = 50

private enum class PlateSheet { ARRANGE_OPTIONS, COPIES }

/**
 * The plate tools along the bottom of the plate: arrange (and its settings),
 * auto-orient, and for the selected object copies / fill bed and delete.
 */
@Composable
internal fun PlateToolbar(state: PrepareUiState, onIntent: (PrepareIntent) -> Unit, modifier: Modifier = Modifier) {
    var sheet by rememberSaveable { mutableStateOf<PlateSheet?>(null) }
    val enabled = state.plateEditable
    val selected = state.selection != null
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(OrcaIcons.Arrange, stringResource(R.string.prepare_tool_arrange), enabled) {
                onIntent(PrepareIntent.Arrange)
            }
            ToolButton(OrcaIcons.Tune, stringResource(R.string.prepare_tool_arrange_options), enabled) {
                sheet = PlateSheet.ARRANGE_OPTIONS
            }
            ToolButton(
                OrcaIcons.LayOnFace,
                stringResource(R.string.prepare_tool_lay_on_face),
                enabled,
                checked = state.layingOnFace,
            ) { onIntent(PrepareIntent.SetLayOnFace(!state.layingOnFace)) }
            ToolButton(
                OrcaIcons.Orient,
                stringResource(if (selected) R.string.prepare_tool_orient else R.string.prepare_tool_orient_all),
                enabled,
            ) { onIntent(PrepareIntent.Orient) }
            ToolButton(OrcaIcons.Measure, stringResource(R.string.prepare_tool_measure), enabled) {
                onIntent(PrepareIntent.OpenMeshTool(MeshTool.MEASURE))
            }
            ToolButton(OrcaIcons.Text, stringResource(R.string.prepare_tool_text), enabled) {
                onIntent(PrepareIntent.OpenMeshTool(MeshTool.EMBOSS))
            }
            if (selected) {
                ToolButton(OrcaIcons.Paint, stringResource(R.string.prepare_tool_paint), enabled) {
                    onIntent(PrepareIntent.StartPainting(com.orcamobile.core.model.PaintKind.SUPPORTS))
                }
                ToolButton(OrcaIcons.Cut, stringResource(R.string.prepare_tool_cut), enabled) {
                    onIntent(PrepareIntent.OpenMeshTool(MeshTool.CUT))
                }
                ToolButton(OrcaIcons.Layers, stringResource(R.string.prepare_tool_layers), enabled) {
                    onIntent(PrepareIntent.OpenMeshTool(MeshTool.LAYERS))
                }
                ToolButton(OrcaIcons.BrimEars, stringResource(R.string.prepare_tool_brim_ears), enabled) {
                    onIntent(PrepareIntent.OpenMeshTool(MeshTool.BRIM_EARS))
                }
                ToolButton(OrcaIcons.Copies, stringResource(R.string.prepare_tool_copies), enabled) {
                    sheet = PlateSheet.COPIES
                }
                ToolButton(Icons.Filled.Delete, stringResource(R.string.prepare_tool_delete), enabled) {
                    onIntent(PrepareIntent.DeleteSelected)
                }
            }
        }
    }

    when (sheet) {
        PlateSheet.ARRANGE_OPTIONS -> ArrangeOptionsSheet(
            options = state.arrangeOptions,
            onChange = { onIntent(PrepareIntent.SetArrangeOptions(it)) },
            onArrange = {
                sheet = null
                onIntent(PrepareIntent.Arrange)
            },
            onDismiss = { sheet = null },
        )
        PlateSheet.COPIES -> CopiesSheet(
            objectName = state.selectedObjectName.orEmpty(),
            onAdd = { count ->
                sheet = null
                onIntent(PrepareIntent.AddCopies(count))
            },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

/**
 * An icon with its name under it: the tools are unfamiliar enough to need words.
 * A [checked] tool is a mode that stays on until tapped again.
 */
@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    checked: Boolean? = null,
    onClick: () -> Unit,
) {
    Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (checked == null) {
            FilledTonalIconButton(onClick = onClick, enabled = enabled) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
            }
        } else {
            FilledIconToggleButton(checked = checked, onCheckedChange = { onClick() }, enabled = enabled) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** What the last plate tool did, or which one is running. Notices fade after a few seconds. */
@Composable
internal fun PlateStatus(state: PrepareUiState, onIntent: (PrepareIntent) -> Unit, modifier: Modifier = Modifier) {
    val notice = state.plateNotice
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MILLIS)
            onIntent(PrepareIntent.DismissPlateNotice)
        }
    }
    val text = state.plateTask?.let { stringResource(it.runningLabel()) }
        ?: notice?.let { noticeText(it) }
        ?: stringResource(R.string.prepare_lay_on_face_hint).takeIf { state.layingOnFace }
        ?: return
    val isError = notice is PlateNotice.Failed && state.plateTask == null
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

private fun PlateTask.runningLabel(): Int = when (this) {
    PlateTask.ARRANGE -> R.string.prepare_tool_arranging
    PlateTask.ORIENT -> R.string.prepare_tool_orienting
    PlateTask.COPY -> R.string.prepare_tool_copying
    PlateTask.FILL_BED -> R.string.prepare_tool_filling
    PlateTask.DELETE -> R.string.prepare_tool_deleting
    PlateTask.SPLIT -> R.string.prepare_tool_splitting
}

@Composable
private fun noticeText(notice: PlateNotice): String = when (notice) {
    is PlateNotice.NotPlaced -> pluralStringResource(R.plurals.prepare_notice_not_placed, notice.count, notice.count)
    is PlateNotice.CopiesAdded -> if (notice.beside > 0) {
        pluralStringResource(R.plurals.prepare_notice_copies_beside, notice.beside, notice.beside)
    } else {
        pluralStringResource(R.plurals.prepare_notice_copies_added, notice.added, notice.added)
    }
    PlateNotice.BedFull -> stringResource(R.string.prepare_notice_bed_full)
    is PlateNotice.Split -> pluralStringResource(R.plurals.prepare_notice_split, notice.count, notice.count)
    is PlateNotice.Cut -> pluralStringResource(R.plurals.prepare_notice_cut, notice.count, notice.count)
    is PlateNotice.Failed -> stringResource(R.string.prepare_notice_failed, notice.message)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArrangeOptionsSheet(
    options: ArrangeOptions,
    onChange: (ArrangeOptions) -> Unit,
    onArrange: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.prepare_arrange_title), style = MaterialTheme.typography.titleLarge)
            val spacing = options.spacingMm.roundToInt()
            Text(
                if (spacing == 0) {
                    stringResource(R.string.prepare_arrange_spacing_auto)
                } else {
                    stringResource(R.string.prepare_arrange_spacing, spacing)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = options.spacingMm.toFloat(),
                onValueChange = { onChange(options.copy(spacingMm = it.roundToInt().toDouble())) },
                valueRange = 0f..ArrangeOptions.MAX_SPACING_MM.toFloat(),
            )
            Text(
                stringResource(R.string.prepare_arrange_spacing_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(stringResource(R.string.prepare_arrange_rotation), options.allowRotation) {
                onChange(options.copy(allowRotation = it))
            }
            SwitchRow(stringResource(R.string.prepare_arrange_align_y), options.alignToYAxis) {
                onChange(options.copy(alignToYAxis = it))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TonalPillButton(
                    stringResource(R.string.prepare_arrange_reset),
                    onClick = { onChange(ArrangeOptions()) },
                    modifier = Modifier.weight(1f),
                )
                PrimaryPillButton(stringResource(R.string.prepare_tool_arrange), onClick = onArrange, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CopiesSheet(objectName: String, onAdd: (Int?) -> Unit, onDismiss: () -> Unit) {
    var count by rememberSaveable { mutableIntStateOf(1) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.prepare_copies_title, objectName), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.prepare_copies_count), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = { count = (count - 1).coerceAtLeast(1) }, enabled = count > 1) {
                    Text("−", style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.Center,
                )
                FilledTonalIconButton(onClick = { count = (count + 1).coerceAtMost(MAX_COPIES) }, enabled = count < MAX_COPIES) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.prepare_copies_more))
                }
            }
            PrimaryPillButton(
                pluralStringResource(R.plurals.prepare_copies_add, count, count),
                onClick = { onAdd(count) },
                modifier = Modifier.fillMaxWidth(),
            )
            TonalPillButton(
                stringResource(R.string.prepare_copies_fill),
                onClick = { onAdd(null) },
                icon = OrcaIcons.FillBed,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.prepare_copies_fill_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
