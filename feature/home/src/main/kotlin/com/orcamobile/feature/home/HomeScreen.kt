package com.orcamobile.feature.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
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

@Composable
fun HomeRoute(onOpenModel: (path: String) -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.OpenModel -> onOpenModel(event.path)
            }
        }
    }
    // Any document type: model files have no reliable MIME type.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onIntent(HomeIntent.ImportModel(it.toString())) }
    }
    HomeScreen(state = state, onIntent = viewModel::onIntent, onPickModel = { picker.launch(arrayOf("*/*")) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeUiState, onIntent: (HomeIntent) -> Unit, onPickModel: () -> Unit) {
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
                OpenModelCard(state, onIntent, onPickModel)
            }
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
private fun OpenModelCard(state: HomeUiState, onIntent: (HomeIntent) -> Unit, onPickModel: () -> Unit) {
    StatusCard(title = stringResource(R.string.home_open_title)) {
        Text(stringResource(R.string.home_open_description), style = MaterialTheme.typography.bodyMedium)
        if (state.importing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickModel, enabled = !state.importing) {
                Text(stringResource(R.string.home_open_model))
            }
            OutlinedButton(onClick = { onIntent(HomeIntent.OpenSample) }, enabled = !state.importing) {
                Text(stringResource(R.string.home_open_sample))
            }
        }
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    OrcaTheme {
        HomeScreen(
            state = HomeUiState(
                engine = EngineUiState.Ready(EngineInfo("OrcaSlicer", "2.6.0-dev", "02.08.01.55", "0c62aa085ea9")),
                printerSetup = PrinterSetupUiState(vendors = listOf("Creality"), vendor = "Creality"),
            ),
            onIntent = {},
            onPickModel = {},
        )
    }
}
