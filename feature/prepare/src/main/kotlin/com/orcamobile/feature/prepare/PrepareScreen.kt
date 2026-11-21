package com.orcamobile.feature.prepare

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orcamobile.core.designsystem.component.ChoiceSheet
import com.orcamobile.core.designsystem.component.DropdownField
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.StatusCard
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.model.BuildPlateSide
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.SliceStatistics
import com.orcamobile.core.model.ToolpathColoring
import com.orcamobile.core.viewport.PlateViewport
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun PrepareRoute(onBack: () -> Unit, onOpenSettings: () -> Unit, viewModel: PrepareViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PrepareScreen(state = state, onIntent = viewModel::onIntent, onBack = onBack, onOpenSettings = onOpenSettings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepareScreen(
    state: PrepareUiState,
    onIntent: (PrepareIntent) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.project?.objects?.firstOrNull()?.name ?: stringResource(R.string.prepare_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.prepare_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        BadgedBox(badge = { if (state.changedSettings > 0) Badge { Text(state.changedSettings.toString()) } }) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.prepare_settings))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val preview = (state.preview as? PreviewUiState.Ready)?.takeIf { state.showingPreview }
            Box(Modifier.fillMaxWidth().weight(0.55f)) {
                PlateViewport(
                    bed = state.bed,
                    project = state.project?.id,
                    preview = preview?.summary?.id,
                    layers = preview?.layers,
                    coloring = preview?.coloring ?: ToolpathColoring.FeatureType,
                    selection = state.selection,
                    onSelectionChange = { onIntent(PrepareIntent.Select(it)) },
                    onPlateEdited = { onIntent(PrepareIntent.PlateEdited) },
                    projectRevision = state.plateRevision,
                    modifier = Modifier.fillMaxSize(),
                )
                val selectedName = state.selectedObjectName
                if (preview == null && selectedName != null) {
                    Text(
                        stringResource(R.string.prepare_selection_hint, selectedName),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(8.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), MaterialTheme.shapes.small)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                if (state.loading || state.preview is PreviewUiState.Loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                if (state.preview is PreviewUiState.Ready) {
                    PlateModeToggle(
                        showingPreview = state.showingPreview,
                        onShowPreview = { onIntent(PrepareIntent.ShowPreview(it)) },
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                PresetBar(state, onIntent, onOpenSettings)
                preview?.let { PreviewCard(it, onIntent) }
                (state.preview as? PreviewUiState.Failed)?.let {
                    Text(stringResource(R.string.prepare_preview_failed, it.message), color = MaterialTheme.colorScheme.error)
                }
                SliceCard(state, onIntent)
                if (state.slice is SliceUiState.Done) PrintHostCard(state.printHost, onIntent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlateModeToggle(showingPreview: Boolean, onShowPreview: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val labels = listOf(R.string.prepare_mode_model, R.string.prepare_mode_preview)
    SingleChoiceSegmentedButtonRow(modifier) {
        labels.forEachIndexed { index, label ->
            SegmentedButton(
                selected = showingPreview == (index == 1),
                onClick = { onShowPreview(index == 1) },
                shape = SegmentedButtonDefaults.itemShape(index, labels.size),
            ) { Text(stringResource(label)) }
        }
    }
}

/** Which preset list a choice sheet is open for. */
private enum class PresetField { VENDOR, PRINTER, PROCESS, FILAMENT }

/**
 * The presets the plate slices with, changeable right here: printer, process and
 * filament chips (shared with the start screen) and the way into every setting.
 */
@Composable
private fun PresetBar(state: PrepareUiState, onIntent: (PrepareIntent) -> Unit, onOpenSettings: () -> Unit) {
    var choosing by rememberSaveable { mutableStateOf<PresetField?>(null) }
    val choices = state.choices
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PresetChip(stringResource(R.string.prepare_preset_vendor), choices.vendor, enabled = choices.vendors.isNotEmpty()) { choosing = PresetField.VENDOR }
        PresetChip(stringResource(R.string.prepare_preset_printer), choices.printer, enabled = choices.printers.isNotEmpty()) { choosing = PresetField.PRINTER }
        PresetChip(stringResource(R.string.prepare_preset_process), choices.process, enabled = choices.processes.isNotEmpty()) { choosing = PresetField.PROCESS }
        PresetChip(stringResource(R.string.prepare_preset_filament), choices.filament, enabled = choices.filaments.isNotEmpty()) { choosing = PresetField.FILAMENT }
        FilledTonalButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                if (state.changedSettings > 0) {
                    stringResource(R.string.prepare_all_settings_changed, state.changedSettings)
                } else {
                    stringResource(R.string.prepare_all_settings)
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
    if (choices.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    choices.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    choosing?.let { field ->
        val (title, options, selected) = when (field) {
            PresetField.VENDOR -> Triple(R.string.prepare_preset_vendor, choices.vendors, choices.vendor)
            PresetField.PRINTER -> Triple(R.string.prepare_preset_printer, choices.printers, choices.printer)
            PresetField.PROCESS -> Triple(R.string.prepare_preset_process, choices.processes, choices.process)
            PresetField.FILAMENT -> Triple(R.string.prepare_preset_filament, choices.filaments, choices.filament)
        }
        ChoiceSheet(
            title = stringResource(title),
            options = options,
            selected = selected,
            onSelect = { choice ->
                onIntent(
                    when (field) {
                        PresetField.VENDOR -> PrepareIntent.SelectVendor(choice)
                        PresetField.PRINTER -> PrepareIntent.SelectPrinter(choice)
                        PresetField.PROCESS -> PrepareIntent.SelectProcess(choice)
                        PresetField.FILAMENT -> PrepareIntent.SelectFilament(choice)
                    },
                )
                // A new vendor needs a printer next.
                if (field == PresetField.VENDOR) choosing = PresetField.PRINTER
            },
            onDismiss = { if (choosing == field) choosing = null },
            searchHint = stringResource(R.string.prepare_search),
        )
    }
}

@Composable
private fun PresetChip(label: String, value: String?, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.widthIn(max = 220.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                value ?: stringResource(R.string.prepare_preset_none),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Which layers to show and what colors the toolpaths, as in the desktop preview's slider and legend. */
@Composable
private fun PreviewCard(preview: PreviewUiState.Ready, onIntent: (PrepareIntent) -> Unit) {
    val summary = preview.summary
    StatusCard(title = stringResource(R.string.prepare_preview_title)) {
        val zs = summary.layerZs
        Text(
            stringResource(
                R.string.prepare_preview_layers,
                preview.layers.first + 1,
                preview.layers.last + 1,
                summary.layerCount,
                String.format(Locale.ROOT, "%.2f", zs.getOrElse(preview.layers.last) { 0f }),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (summary.layerCount > 1) {
            RangeSlider(
                value = preview.layers.first.toFloat()..preview.layers.last.toFloat(),
                onValueChange = { range ->
                    onIntent(PrepareIntent.SetPreviewLayers(range.start.roundToInt()..range.endInclusive.roundToInt()))
                },
                valueRange = 0f..(summary.layerCount - 1).toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val coloringLabels = ToolpathColoring.entries.associateWith { stringResource(it.labelRes()) }
        DropdownField(
            label = stringResource(R.string.prepare_preview_coloring),
            options = coloringLabels.values.toList(),
            selected = coloringLabels.getValue(preview.coloring),
            onSelect = { label ->
                coloringLabels.entries.firstOrNull { it.value == label }?.let { onIntent(PrepareIntent.SetPreviewColoring(it.key)) }
            },
        )
    }
}

private fun ToolpathColoring.labelRes(): Int = when (this) {
    ToolpathColoring.FeatureType -> R.string.prepare_coloring_feature_type
    ToolpathColoring.Height -> R.string.prepare_coloring_height
    ToolpathColoring.Width -> R.string.prepare_coloring_width
    ToolpathColoring.Speed -> R.string.prepare_coloring_speed
    ToolpathColoring.FanSpeed -> R.string.prepare_coloring_fan_speed
    ToolpathColoring.Temperature -> R.string.prepare_coloring_temperature
    ToolpathColoring.VolumetricFlowRate -> R.string.prepare_coloring_flow
    ToolpathColoring.LayerTime -> R.string.prepare_coloring_layer_time
    ToolpathColoring.Filament -> R.string.prepare_coloring_filament
}

@Composable
private fun SliceCard(state: PrepareUiState, onIntent: (PrepareIntent) -> Unit) {
    StatusCard(title = stringResource(R.string.prepare_slice_title)) {
        if (state.presets == null) {
            Text(stringResource(R.string.prepare_slice_no_presets), style = MaterialTheme.typography.bodySmall)
        }
        if (state.changedSettings > 0) {
            Text(
                stringResource(R.string.prepare_settings_changed, state.changedSettings),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        state.project?.let { project ->
            val triangles = project.objects.sumOf { it.triangles }
            Text(
                stringResource(R.string.prepare_model_info, project.objects.size, triangles),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        when (val slice = state.slice) {
            SliceUiState.Idle -> Unit
            is SliceUiState.Running -> {
                LinearProgressIndicator(progress = { slice.percent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                Text("${slice.percent}% · ${slice.message}", style = MaterialTheme.typography.bodySmall)
            }
            is SliceUiState.Done -> SliceStatisticsView(slice.statistics)
            SliceUiState.Canceled -> Text(stringResource(R.string.prepare_slice_canceled))
            is SliceUiState.Failed -> Text(slice.message, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val running = state.slice is SliceUiState.Running
            if (running) {
                TonalPillButton(
                    text = stringResource(R.string.prepare_slice_cancel),
                    onClick = { onIntent(PrepareIntent.CancelSlice) },
                    modifier = Modifier.weight(1f),
                )
            }
            PrimaryPillButton(
                text = stringResource(R.string.prepare_slice_action),
                onClick = { onIntent(PrepareIntent.Slice) },
                enabled = state.project != null && !running,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SliceStatisticsView(statistics: SliceStatistics) {
    val minutes = (statistics.printTimeSeconds / 60).toInt()
    Text(stringResource(R.string.prepare_stats_time, minutes / 60, minutes % 60))
    Text(
        stringResource(
            R.string.prepare_stats_filament,
            String.format(Locale.ROOT, "%.2f", statistics.filamentUsedMm / 1000),
            String.format(Locale.ROOT, "%.1f", statistics.filamentUsedGrams),
        ),
    )
    Text(stringResource(R.string.prepare_stats_layers, statistics.layerCount))
}

/** How the printer starts the print: the choices desktop OrcaSlicer offers for ElegooLink. */
@Composable
private fun StartOptions(options: PrintStartOptions, enabled: Boolean, onIntent: (PrepareIntent) -> Unit) {
    Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledSwitch(stringResource(R.string.prepare_start_bed_leveling), options.heatedBedLeveling, enabled) {
            onIntent(PrepareIntent.SetHeatedBedLeveling(it))
        }
        LabeledSwitch(stringResource(R.string.prepare_start_time_lapse), options.timeLapse, enabled) {
            onIntent(PrepareIntent.SetTimeLapse(it))
        }
        Text(stringResource(R.string.prepare_start_plate), style = MaterialTheme.typography.labelLarge)
        BuildPlateSide.entries.forEach { side ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = options.plate == side, enabled = enabled, role = Role.RadioButton) {
                        onIntent(PrepareIntent.SetBuildPlate(side))
                    },
            ) {
                RadioButton(selected = options.plate == side, onClick = null, enabled = enabled)
                Text(
                    stringResource(
                        when (side) {
                            BuildPlateSide.TEXTURED -> R.string.prepare_start_plate_textured
                            BuildPlateSide.SMOOTH -> R.string.prepare_start_plate_smooth
                        },
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun PrintHostCard(host: PrintHostUiState, onIntent: (PrepareIntent) -> Unit) {
    val busy = host.status is PrintHostStatus.Testing || host.status is PrintHostStatus.Sending
    StatusCard(title = stringResource(R.string.prepare_host_title)) {
        DropdownField(
            label = stringResource(R.string.prepare_host_type),
            options = PrintHostType.entries.map { it.displayName },
            selected = host.type.displayName,
            onSelect = { name ->
                PrintHostType.entries.firstOrNull { it.displayName == name }?.let { onIntent(PrepareIntent.SetHostType(it)) }
            },
            enabled = !busy,
        )
        OutlinedTextField(
            value = host.address,
            onValueChange = { onIntent(PrepareIntent.SetHostAddress(it)) },
            label = { Text(stringResource(R.string.prepare_host_address)) },
            placeholder = { Text("192.168.1.50") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = host.apiKey,
            onValueChange = { onIntent(PrepareIntent.SetHostApiKey(it)) },
            label = { Text(stringResource(R.string.prepare_host_api_key)) },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = host.startPrint, onCheckedChange = { onIntent(PrepareIntent.SetStartPrint(it)) }, enabled = !busy)
            Text(stringResource(R.string.prepare_host_start_print))
        }
        if (host.startPrint && host.type.supportsStartOptions) StartOptions(host.startOptions, enabled = !busy, onIntent)
        when (val status = host.status) {
            PrintHostStatus.Idle -> Unit
            PrintHostStatus.Testing -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            is PrintHostStatus.Connected -> Text(stringResource(R.string.prepare_host_connected, status.description))
            is PrintHostStatus.Sending -> {
                LinearProgressIndicator(progress = { status.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.prepare_host_sending, status.percent), style = MaterialTheme.typography.bodySmall)
            }
            is PrintHostStatus.Sent -> Text(
                stringResource(if (status.printing) R.string.prepare_host_sent_printing else R.string.prepare_host_sent),
            )
            is PrintHostStatus.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val ready = host.config != null && !busy
            TonalPillButton(
                text = stringResource(R.string.prepare_host_test),
                onClick = { onIntent(PrepareIntent.TestPrintHost) },
                enabled = ready,
                modifier = Modifier.weight(1f),
            )
            PrimaryPillButton(
                text = stringResource(R.string.prepare_host_send),
                onClick = { onIntent(PrepareIntent.SendToPrinter) },
                enabled = ready,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
