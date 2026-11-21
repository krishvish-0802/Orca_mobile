package com.orcamobile.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orcamobile.core.designsystem.component.DropdownField
import com.orcamobile.core.designsystem.component.StatusCard
import com.orcamobile.core.designsystem.theme.OrcaTheme
import com.orcamobile.core.model.EngineInfo
import com.orcamobile.core.model.SliceStatistics
import java.util.Locale

@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(state = state, onIntent = viewModel::onIntent)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeUiState, onIntent: (HomeIntent) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.home_title)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EngineCard(state.engine)
            if (state.engine is EngineUiState.Ready) {
                PrinterSetupCard(state.printerSetup, onIntent)
            }
            SliceCard(
                slice = state.slice,
                engineReady = state.engine is EngineUiState.Ready,
                onIntent = onIntent,
            )
        }
    }
}

@Composable
private fun EngineCard(engine: EngineUiState) {
    StatusCard(title = stringResource(R.string.home_engine_title)) {
        when (engine) {
            EngineUiState.Loading -> Text(stringResource(R.string.home_engine_loading))
            is EngineUiState.Ready -> {
                Text("${engine.info.appName} ${engine.info.orcaVersion}")
                Text(
                    stringResource(R.string.home_engine_commit, engine.info.upstreamCommit),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is EngineUiState.Unavailable -> Text(
                stringResource(R.string.home_engine_unavailable, engine.reason),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PrinterSetupCard(setup: PrinterSetupUiState, onIntent: (HomeIntent) -> Unit) {
    StatusCard(title = stringResource(R.string.home_printer_title)) {
        DropdownField(
            label = stringResource(R.string.home_printer_vendor),
            options = setup.vendors,
            selected = setup.vendor,
            onSelect = { onIntent(HomeIntent.SelectVendor(it)) },
        )
        DropdownField(
            label = stringResource(R.string.home_printer_printer),
            options = setup.printers,
            selected = setup.printer,
            onSelect = { onIntent(HomeIntent.SelectPrinter(it)) },
        )
        DropdownField(
            label = stringResource(R.string.home_printer_process),
            options = setup.processes,
            selected = setup.process,
            onSelect = { onIntent(HomeIntent.SelectProcess(it)) },
        )
        DropdownField(
            label = stringResource(R.string.home_printer_filament),
            options = setup.filaments,
            selected = setup.filament,
            onSelect = { onIntent(HomeIntent.SelectFilament(it)) },
        )
        when {
            setup.loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            setup.error != null -> Text(setup.error, color = MaterialTheme.colorScheme.error)
            setup.selection == null -> Text(
                stringResource(R.string.home_printer_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SliceCard(slice: SliceUiState, engineReady: Boolean, onIntent: (HomeIntent) -> Unit) {
    StatusCard(title = stringResource(R.string.home_slice_title)) {
        Text(stringResource(R.string.home_slice_description), style = MaterialTheme.typography.bodyMedium)
        when (slice) {
            SliceUiState.Idle -> Unit
            is SliceUiState.Running -> {
                LinearProgressIndicator(
                    progress = { slice.percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${slice.percent}% · ${slice.message}", style = MaterialTheme.typography.bodySmall)
            }
            is SliceUiState.Done -> SliceStatisticsView(slice.statistics)
            SliceUiState.Canceled -> Text(stringResource(R.string.home_slice_canceled))
            is SliceUiState.Failed -> Text(slice.message, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val running = slice is SliceUiState.Running
            Button(onClick = { onIntent(HomeIntent.SliceSample) }, enabled = engineReady && !running) {
                Text(stringResource(R.string.home_slice_action))
            }
            if (running) {
                OutlinedButton(onClick = { onIntent(HomeIntent.CancelSlice) }) {
                    Text(stringResource(R.string.home_slice_cancel))
                }
            }
        }
    }
}

@Composable
private fun SliceStatisticsView(statistics: SliceStatistics) {
    val minutes = (statistics.printTimeSeconds / 60).toInt()
    Text(stringResource(R.string.home_stats_time, minutes / 60, minutes % 60))
    Text(
        stringResource(
            R.string.home_stats_filament,
            String.format(Locale.ROOT, "%.2f", statistics.filamentUsedMm / 1000),
            String.format(Locale.ROOT, "%.1f", statistics.filamentUsedGrams),
        ),
    )
    Text(stringResource(R.string.home_stats_layers, statistics.layerCount))
    Text(stringResource(R.string.home_stats_size, statistics.gcodeBytes / 1024))
}

@Preview
@Composable
private fun HomeScreenPreview() {
    OrcaTheme {
        HomeScreen(
            state = HomeUiState(
                engine = EngineUiState.Ready(EngineInfo("OrcaSlicer", "2.6.0-dev", "02.08.01.55", "0c62aa085ea9")),
                slice = SliceUiState.Done(SliceStatistics(1834.0, 3120.0, 9.3, 100, 812_000), "/gcode/cube.gcode"),
            ),
            onIntent = {},
        )
    }
}
