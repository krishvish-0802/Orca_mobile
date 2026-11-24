package com.orcamobile.feature.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orcamobile.core.designsystem.component.ChoiceSheet
import com.orcamobile.core.designsystem.component.ListGroup
import com.orcamobile.core.designsystem.component.ListRow
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.SectionHeader
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.designsystem.theme.OrcaTheme
import com.orcamobile.core.model.EngineInfo
import com.orcamobile.core.model.PresetKind

@Composable
fun HomeRoute(
    onOpenModel: (path: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // On start and whenever the app comes back: is there a newer build?
    LifecycleResumeEffect(viewModel) {
        viewModel.onIntent(HomeIntent.CheckForUpdate)
        onPauseOrDispose { }
    }
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
    HomeScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onPickModel = { picker.launch(arrayOf("*/*")) },
        onOpenSettings = onOpenSettings,
    )
}

/** Which preset list a choice sheet is open for. */
private enum class PresetField(val kind: PresetKind?) {
    VENDOR(null),
    PRINTER(PresetKind.PRINTER),
    PROCESS(PresetKind.PROCESS),
    FILAMENT(PresetKind.FILAMENT),
}

@Composable
fun HomeScreen(state: HomeUiState, onIntent: (HomeIntent) -> Unit, onPickModel: () -> Unit, onOpenSettings: () -> Unit) {
    var choosing by rememberSaveable { mutableStateOf<PresetField?>(null) }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(state.engine)
            UpdateCard(state.update, onIntent)
            if (state.engine is EngineUiState.Ready) {
                StartCard(state, onIntent, onPickModel)
                PrinterSection(state.printerSetup, onChoose = { choosing = it }, onOpenSettings = onOpenSettings)
            }
        }
    }
    choosing?.let { field ->
        val setup = state.printerSetup
        val (title, options, selected) = when (field) {
            PresetField.VENDOR -> Triple(R.string.home_printer_vendor, setup.vendors, setup.vendor)
            PresetField.PRINTER -> Triple(R.string.home_printer_printer, setup.printers, setup.printer)
            PresetField.PROCESS -> Triple(R.string.home_printer_process, setup.processes, setup.process)
            PresetField.FILAMENT -> Triple(R.string.home_printer_filament, setup.filaments, setup.filament)
        }
        ChoiceSheet(
            title = stringResource(title),
            options = options,
            selected = selected,
            onSelect = { choice ->
                onIntent(
                    when (field) {
                        PresetField.VENDOR -> HomeIntent.SelectVendor(choice)
                        PresetField.PRINTER -> HomeIntent.SelectPrinter(choice)
                        PresetField.PROCESS -> HomeIntent.SelectProcess(choice)
                        PresetField.FILAMENT -> HomeIntent.SelectFilament(choice)
                    },
                )
            },
            onDismiss = { choosing = null },
            searchHint = stringResource(R.string.home_search),
            marked = field.kind?.let(setup::userNames).orEmpty(),
            markLabel = stringResource(R.string.home_preset_yours),
        )
    }
}

@Composable
private fun Header(engine: EngineUiState) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.displaySmall)
        when (engine) {
            EngineUiState.Loading -> Text(stringResource(R.string.home_engine_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            is EngineUiState.Ready -> Text(
                stringResource(R.string.home_engine_ready, engine.info.orcaVersion, engine.info.upstreamCommit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is EngineUiState.Unavailable -> Text(
                stringResource(R.string.home_engine_unavailable, engine.reason),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The way in: open a model, or try the sample cube. */
@Composable
private fun StartCard(state: HomeUiState, onIntent: (HomeIntent) -> Unit, onPickModel: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.home_open_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                stringResource(R.string.home_open_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (state.importing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            state.importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryPillButton(
                    text = stringResource(R.string.home_open_model),
                    icon = Icons.Filled.Add,
                    onClick = onPickModel,
                    enabled = !state.importing,
                    modifier = Modifier.weight(1f),
                )
                TonalPillButton(
                    text = stringResource(R.string.home_open_sample),
                    onClick = { onIntent(HomeIntent.OpenSample) },
                    enabled = !state.importing,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PrinterSection(setup: PrinterSetupUiState, onChoose: (PresetField) -> Unit, onOpenSettings: () -> Unit) {
    SectionHeader(stringResource(R.string.home_printer_title))
    val notChosen = stringResource(R.string.home_not_chosen)
    val chevron: @Composable RowScope.() -> Unit = {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val rows = listOf(
        Triple(PresetField.VENDOR, R.string.home_printer_vendor, setup.vendor to setup.vendors.isNotEmpty()),
        Triple(PresetField.PRINTER, R.string.home_printer_printer, setup.printer to setup.printers.isNotEmpty()),
        Triple(PresetField.PROCESS, R.string.home_printer_process, setup.process to setup.processes.isNotEmpty()),
        Triple(PresetField.FILAMENT, R.string.home_printer_filament, setup.filament to setup.filaments.isNotEmpty()),
    )
    ListGroup(items = rows) { (field, label, value) ->
        val (selected, available) = value
        ListRow(
            title = stringResource(label),
            supporting = selected ?: notChosen,
            enabled = available,
            onClick = { onChoose(field) },
            trailing = chevron,
        )
    }
    when {
        setup.loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        setup.error != null -> Text(setup.error.orEmpty(), color = MaterialTheme.colorScheme.error)
        setup.selection == null -> Text(
            stringResource(R.string.home_printer_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    ListGroup(items = listOf(Unit)) {
        ListRow(
            title = stringResource(R.string.home_print_settings),
            supporting = stringResource(R.string.home_print_settings_description),
            leading = { Icon(Icons.Filled.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            onClick = onOpenSettings,
            trailing = chevron,
        )
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    OrcaTheme {
        HomeScreen(
            state = HomeUiState(
                engine = EngineUiState.Ready(EngineInfo("OrcaSlicer", "2.6.0-dev", "02.08.01.55", "0c62aa085ea9")),
                printerSetup = PrinterSetupUiState(vendors = listOf("Elegoo"), vendor = "Elegoo"),
            ),
            onIntent = {},
            onPickModel = {},
            onOpenSettings = {},
        )
    }
}
