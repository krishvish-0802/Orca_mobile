package com.orcamobile.feature.devices

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orcamobile.core.designsystem.component.ListGroup
import com.orcamobile.core.designsystem.component.ListRow
import com.orcamobile.core.designsystem.component.StatusCard
import com.orcamobile.core.model.Axis
import com.orcamobile.core.model.FanKind
import com.orcamobile.core.model.Heater
import com.orcamobile.core.model.HeaterKind
import com.orcamobile.core.model.MachineState
import com.orcamobile.core.model.PrintRecord
import com.orcamobile.core.model.PrinterFile
import com.orcamobile.core.model.PrinterLink
import com.orcamobile.core.model.PrinterState
import java.text.DateFormat
import java.util.Date

@Composable
fun PrinterRoute(onBack: () -> Unit, onEdit: (String) -> Unit, viewModel: PrinterViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PrinterScreen(state, viewModel::onIntent, onBack, onEdit)
}

/** One printer, as the desktop's Device tab shows it: status, camera, controls, files and history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterScreen(state: PrinterUiState, onIntent: (PrinterIntent) -> Unit, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val printer = state.printer
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(printer?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        state.status?.let { Text(statusText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.devices_back)) }
                },
                actions = {
                    if (printer != null) {
                        IconButton(onClick = { onEdit(printer.id) }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.devices_edit_title)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.problem?.let { problem ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(problem, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onIntent(PrinterIntent.DismissProblem) }) { Text(stringResource(R.string.devices_ok)) }
                    }
                }
            }
            val status = state.status
            if (status == null) {
                Text(stringResource(R.string.devices_upload_only_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            (status.link as? PrinterLink.Offline)?.let { offline ->
                StatusCard(title = stringResource(R.string.devices_offline)) {
                    Text(offline.reason ?: stringResource(R.string.devices_offline_body), style = MaterialTheme.typography.bodySmall)
                }
            }
            status.message?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
            }
            if (state.abilities.camera) CameraCard(state.camera, enabled = status.link == PrinterLink.Online) { onIntent(PrinterIntent.ToggleCamera) }
            JobCard(status, state.abilities, onIntent)
            if (status.heaters.isNotEmpty()) TemperatureCard(status.heaters, state.abilities.heaters, onIntent)
            if (state.abilities.fans.isNotEmpty() || state.abilities.speed != null || state.abilities.light || state.abilities.home || state.abilities.jog) {
                ControlsCard(status, state.abilities, onIntent)
            }
            if (status.filamentSlots.isNotEmpty()) FilamentCard(status)
            if (state.abilities.files) FilesCard(state.files, onIntent, canPrint = !status.machine.active && status.link == PrinterLink.Online)
            if (state.abilities.history) HistoryCard(state.history) { onIntent(PrinterIntent.LoadHistory) }
            if (state.abilities.console) ConsoleCard(state.console, state.abilities.consoleOutput, enabled = status.link == PrinterLink.Online, onIntent)
            listOfNotNull(status.model, status.firmware?.let { stringResource(R.string.devices_firmware, it) }).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CameraCard(camera: CameraUi, enabled: Boolean, onToggle: () -> Unit) {
    StatusCard(title = stringResource(R.string.devices_camera)) {
        when (camera) {
            CameraUi.Off -> OutlinedButton(onClick = onToggle, enabled = enabled) { Text(stringResource(R.string.devices_camera_show)) }
            CameraUi.Starting -> CircularProgressIndicator(Modifier.size(24.dp))
            is CameraUi.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(camera.message, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onToggle, enabled = enabled) { Text(stringResource(R.string.devices_camera_retry)) }
            }
            is CameraUi.Showing -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    val frame = camera.frame
                    if (frame == null) CircularProgressIndicator() else Image(frame, contentDescription = stringResource(R.string.devices_camera), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                TextButton(onClick = onToggle) { Text(stringResource(R.string.devices_camera_hide)) }
            }
        }
    }
}

@Composable
private fun JobCard(status: PrinterState, abilities: PrinterAbilities, onIntent: (PrinterIntent) -> Unit) {
    var confirmStop by remember { mutableStateOf(false) }
    StatusCard(title = statusText(status)) {
        val job = status.job
        if (job == null) {
            Text(stringResource(R.string.devices_no_job), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(job.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            job.progress?.let { progress ->
                LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
            val details = listOfNotNull(
                if (job.currentLayer != null && job.totalLayers != null) stringResource(R.string.devices_layer, job.currentLayer!!, job.totalLayers!!) else null,
                job.elapsedSeconds?.let { stringResource(R.string.devices_elapsed, duration(it)) },
                job.remainingSeconds?.takeIf { status.machine.active }?.let { stringResource(R.string.devices_left, duration(it)) },
            )
            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (abilities.jobs && status.machine.active && status.link == PrinterLink.Online) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.machine == MachineState.PAUSED) {
                    FilledTonalButton(onClick = { onIntent(PrinterIntent.Resume) }) { Text(stringResource(R.string.devices_resume)) }
                } else {
                    FilledTonalButton(onClick = { onIntent(PrinterIntent.Pause) }, enabled = status.machine == MachineState.PRINTING) {
                        Text(stringResource(R.string.devices_pause))
                    }
                }
                OutlinedButton(onClick = { confirmStop = true }, enabled = status.machine != MachineState.STOPPING) { Text(stringResource(R.string.devices_stop)) }
            }
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.devices_stop_title)) },
            text = { Text(stringResource(R.string.devices_stop_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    onIntent(PrinterIntent.Stop)
                }) { Text(stringResource(R.string.devices_stop)) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.devices_keep_printing)) } },
        )
    }
}

@Composable
private fun TemperatureCard(heaters: List<Heater>, settable: Set<HeaterKind>, onIntent: (PrinterIntent) -> Unit) {
    var editing by remember { mutableStateOf<Heater?>(null) }
    StatusCard(title = stringResource(R.string.devices_temperatures)) {
        ListGroup(items = heaters) { heater ->
            ListRow(
                title = heaterName(heater.kind),
                supporting = if (heater.target > 0) "${heater.current.toInt()}° → ${heater.target.toInt()}°" else "${heater.current.toInt()}°",
                onClick = if (heater.kind in settable) ({ editing = heater }) else null,
            )
        }
    }
    editing?.let { heater ->
        var text by remember(heater.kind) { mutableStateOf(heater.target.toInt().toString()) }
        val max = heater.maxTarget?.toInt() ?: 300
        val value = text.toIntOrNull()
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.devices_set_temperature, heaterName(heater.kind))) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(3) },
                    suffix = { Text("°C") },
                    supportingText = { Text(stringResource(R.string.devices_temperature_hint, max)) },
                    isError = value == null || value > max,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onIntent(PrinterIntent.SetTemperature(heater.kind, value ?: 0))
                    editing = null
                }, enabled = value != null && value <= max) { Text(stringResource(R.string.devices_set)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.devices_cancel)) } },
        )
    }
}

@Composable
private fun ControlsCard(status: PrinterState, abilities: PrinterAbilities, onIntent: (PrinterIntent) -> Unit) {
    val online = status.link == PrinterLink.Online
    StatusCard(title = stringResource(R.string.devices_controls)) {
        abilities.speed?.let { range ->
            PercentSlider(stringResource(R.string.devices_speed), status.speedPercent ?: 100, range, online) { onIntent(PrinterIntent.SetSpeed(it)) }
        }
        for (fan in listOf(FanKind.PART, FanKind.AUXILIARY, FanKind.CHAMBER).filter { it in abilities.fans }) {
            PercentSlider(fanName(fan), status.fans.firstOrNull { it.kind == fan }?.percent ?: 0, 0..100, online) { onIntent(PrinterIntent.SetFan(fan, it)) }
        }
        if (abilities.light) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.devices_light), modifier = Modifier.weight(1f))
                Switch(checked = status.lightOn == true, onCheckedChange = { onIntent(PrinterIntent.SetLight(it)) }, enabled = online)
            }
        }
        if (abilities.home) {
            OutlinedButton(onClick = { onIntent(PrinterIntent.Home) }, enabled = online && !status.machine.active) { Text(stringResource(R.string.devices_home)) }
        }
        if (abilities.jog) JogPad(enabled = online && !status.machine.active, onIntent)
    }
}

/** Moves the toolhead by a chosen step, as the desktop's and Mainsail's jog controls. */
@Composable
private fun JogPad(enabled: Boolean, onIntent: (PrinterIntent) -> Unit) {
    var step by remember { mutableStateOf(JOG_STEPS[1]) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.devices_jog), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in JOG_STEPS) {
                FilterChip(selected = step == choice, onClick = { step = choice }, label = { Text(stringResource(R.string.devices_jog_step, formatStep(choice))) })
            }
        }
        for (axis in Axis.entries) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(axis.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.size(width = 24.dp, height = 24.dp))
                OutlinedButton(onClick = { onIntent(PrinterIntent.Jog(axis, -step)) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text("−${formatStep(step)}")
                }
                OutlinedButton(onClick = { onIntent(PrinterIntent.Jog(axis, step)) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text("+${formatStep(step)}")
                }
            }
        }
    }
}

private val JOG_STEPS = listOf(0.1, 1.0, 10.0, 50.0)

private fun formatStep(step: Double) = if (step % 1.0 == 0.0) step.toInt().toString() else step.toString()

/** G-code to the printer, and what it answers when it does. */
@Composable
private fun ConsoleCard(lines: List<String>?, answers: Boolean, enabled: Boolean, onIntent: (PrinterIntent) -> Unit) {
    StatusCard(title = stringResource(R.string.devices_console)) {
        if (lines == null) {
            OutlinedButton(onClick = { onIntent(PrinterIntent.ToggleConsole) }) { Text(stringResource(R.string.devices_console_open)) }
            return@StatusCard
        }
        if (!answers) Text(stringResource(R.string.devices_console_no_answers), style = MaterialTheme.typography.bodySmall)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            val scroll = rememberScrollState()
            LaunchedEffect(lines.size) { scroll.scrollTo(scroll.maxValue) }
            Column(Modifier.heightIn(min = 80.dp, max = 240.dp).verticalScroll(scroll).padding(8.dp)) {
                for (line in lines) Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        var gcode by remember { mutableStateOf("") }
        val send = {
            onIntent(PrinterIntent.SendGcode(gcode))
            gcode = ""
        }
        OutlinedTextField(
            value = gcode,
            onValueChange = { gcode = it },
            label = { Text(stringResource(R.string.devices_console_gcode)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, capitalization = KeyboardCapitalization.Characters),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = send, enabled = enabled && gcode.isNotBlank()) { Text(stringResource(R.string.devices_console_send)) }
            TextButton(onClick = { onIntent(PrinterIntent.ToggleConsole) }) { Text(stringResource(R.string.devices_console_close)) }
        }
    }
}

/** A percentage that is sent when the finger lifts, not on every step. */
@Composable
private fun PercentSlider(label: String, value: Int, range: IntRange, enabled: Boolean, onSet: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableFloatStateOf(value.toFloat()) }
    if (!dragging) shown = value.toFloat()
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text("${shown.toInt()}%", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = shown.coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = {
                dragging = true
                shown = it
            },
            onValueChangeFinished = {
                dragging = false
                onSet(shown.toInt())
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
        )
    }
}

@Composable
private fun FilamentCard(status: PrinterState) {
    StatusCard(title = stringResource(R.string.devices_filament)) {
        status.filamentSlots.forEach { slot ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val colour = slot.colour?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Color.Gray
                Box(Modifier.size(20.dp).background(if (slot.loaded) colour else Color.Transparent, CircleShape))
                Text(
                    stringResource(R.string.devices_slot, slot.index + 1) + " · " +
                        (if (slot.loaded) listOfNotNull(slot.type, slot.name).joinToString(" ").ifBlank { stringResource(R.string.devices_slot_loaded) } else stringResource(R.string.devices_slot_empty)),
                    style = if (slot.active) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    color = if (slot.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun FilesCard(files: ListUi<PrinterFile>, onIntent: (PrinterIntent) -> Unit, canPrint: Boolean) {
    var chosen by remember { mutableStateOf<PrinterFile?>(null) }
    StatusCard(title = stringResource(R.string.devices_files)) {
        when (files) {
            ListUi.NotLoaded -> OutlinedButton(onClick = { onIntent(PrinterIntent.LoadFiles) }) { Text(stringResource(R.string.devices_files_show)) }
            ListUi.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is ListUi.Failed -> Text(files.message, color = MaterialTheme.colorScheme.error)
            is ListUi.Loaded -> if (files.items.isEmpty()) {
                Text(stringResource(R.string.devices_files_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ListGroup(items = files.items.filterNot { it.isFolder }) { file ->
                    ListRow(
                        title = file.name,
                        supporting = file.sizeBytes?.let { "%.1f MB".format(it / 1_048_576.0) },
                        onClick = { chosen = file },
                    )
                }
            }
        }
    }
    chosen?.let { file ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(file.name) },
            confirmButton = {
                TextButton(onClick = {
                    onIntent(PrinterIntent.PrintFile(file))
                    chosen = null
                }, enabled = canPrint) { Text(stringResource(R.string.devices_print)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    onIntent(PrinterIntent.DeleteFile(file))
                    chosen = null
                }) { Text(stringResource(R.string.devices_delete)) }
            },
        )
    }
}

@Composable
private fun HistoryCard(history: ListUi<PrintRecord>, onLoad: () -> Unit) {
    StatusCard(title = stringResource(R.string.devices_history)) {
        when (history) {
            ListUi.NotLoaded -> OutlinedButton(onClick = onLoad) { Text(stringResource(R.string.devices_history_show)) }
            ListUi.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is ListUi.Failed -> Text(history.message, color = MaterialTheme.colorScheme.error)
            is ListUi.Loaded -> if (history.items.isEmpty()) {
                Text(stringResource(R.string.devices_history_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
                ListGroup(items = history.items) { record ->
                    val outcome = when (record.outcome) {
                        MachineState.FINISHED -> stringResource(R.string.devices_finished)
                        MachineState.CANCELLED -> stringResource(R.string.devices_cancelled)
                        MachineState.ERROR -> record.reason ?: stringResource(R.string.devices_error)
                        else -> ""
                    }
                    ListRow(
                        title = record.fileName,
                        supporting = listOfNotNull(record.startedEpochSeconds?.let { format.format(Date(it * 1000)) }, outcome.ifBlank { null }).joinToString(" · "),
                    )
                }
            }
        }
    }
}
