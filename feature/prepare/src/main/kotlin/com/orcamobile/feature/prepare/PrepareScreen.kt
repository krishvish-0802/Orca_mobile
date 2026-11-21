package com.orcamobile.feature.prepare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orcamobile.core.designsystem.component.DropdownField
import com.orcamobile.core.designsystem.component.StatusCard
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.model.SliceStatistics
import com.orcamobile.core.viewport.PlateViewport
import java.util.Locale

@Composable
fun PrepareRoute(onBack: () -> Unit, viewModel: PrepareViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PrepareScreen(state = state, onIntent = viewModel::onIntent, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepareScreen(state: PrepareUiState, onIntent: (PrepareIntent) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.project?.objects?.firstOrNull()?.name ?: stringResource(R.string.prepare_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.prepare_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().weight(0.55f)) {
                PlateViewport(bed = state.bed, project = state.project?.id, modifier = Modifier.fillMaxSize())
                if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
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
                SliceCard(state, onIntent)
                if (state.slice is SliceUiState.Done) PrintHostCard(state.printHost, onIntent)
            }
        }
    }
}

@Composable
private fun SliceCard(state: PrepareUiState, onIntent: (PrepareIntent) -> Unit) {
    StatusCard(title = stringResource(R.string.prepare_slice_title)) {
        val presets = state.presets
        Text(
            if (presets != null) {
                stringResource(R.string.prepare_slice_presets, presets.printer, presets.process, presets.filaments.joinToString())
            } else {
                stringResource(R.string.prepare_slice_no_presets)
            },
            style = MaterialTheme.typography.bodySmall,
        )
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val running = state.slice is SliceUiState.Running
            Button(onClick = { onIntent(PrepareIntent.Slice) }, enabled = state.project != null && !running) {
                Text(stringResource(R.string.prepare_slice_action))
            }
            if (running) {
                OutlinedButton(onClick = { onIntent(PrepareIntent.CancelSlice) }) {
                    Text(stringResource(R.string.prepare_slice_cancel))
                }
            }
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
            placeholder = { Text("192.168.1.50:7125") },
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ready = host.config != null && !busy
            OutlinedButton(onClick = { onIntent(PrepareIntent.TestPrintHost) }, enabled = ready) {
                Text(stringResource(R.string.prepare_host_test))
            }
            Button(onClick = { onIntent(PrepareIntent.SendToPrinter) }, enabled = ready) {
                Text(stringResource(R.string.prepare_host_send))
            }
        }
    }
}
