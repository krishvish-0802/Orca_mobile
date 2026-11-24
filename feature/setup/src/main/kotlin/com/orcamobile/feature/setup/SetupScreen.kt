package com.orcamobile.feature.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orcamobile.core.designsystem.component.ChoiceSheet
import com.orcamobile.core.designsystem.component.ListGroup
import com.orcamobile.core.designsystem.component.ListRow
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.model.PrintHostType

@Composable
fun SetupRoute(onFinished: () -> Unit, viewModel: SetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                SetupEvent.Finished -> onFinished()
            }
        }
    }
    SetupScreen(state, viewModel::onIntent)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(state: SetupUiState, onIntent: (SetupIntent) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.setup_title))
                        Text(
                            stringResource(R.string.setup_step, state.step.ordinal + 1, SetupStep.entries.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = { TextButton(onClick = { onIntent(SetupIntent.Skip) }) { Text(stringResource(R.string.setup_skip)) } },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.step != SetupStep.PRINTER) {
                    TonalPillButton(stringResource(R.string.setup_back), onClick = { onIntent(SetupIntent.Back) }, modifier = Modifier.weight(1f))
                }
                PrimaryPillButton(
                    stringResource(if (state.step == SetupStep.CONNECTION) R.string.setup_finish else R.string.setup_next),
                    onClick = { onIntent(SetupIntent.Next) },
                    enabled = state.canGoOn,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when (state.step) {
                SetupStep.PRINTER -> PrinterPage(state, onIntent)
                SetupStep.FILAMENTS -> FilamentsPage(state, onIntent)
                SetupStep.CONNECTION -> ConnectionPage(state.host, onIntent)
            }
        }
    }
}

private enum class Choosing { VENDOR, PRINTER }

@Composable
private fun PrinterPage(state: SetupUiState, onIntent: (SetupIntent) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf<Choosing?>(null) }
    val choices = state.choices
    Text(stringResource(R.string.setup_printer_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.setup_printer_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
    val chevron: @Composable RowScope.() -> Unit = {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val notChosen = stringResource(R.string.setup_not_chosen)
    ListGroup(
        rows = listOf(
            {
                ListRow(
                    title = stringResource(R.string.setup_vendor),
                    supporting = choices.vendor ?: notChosen,
                    enabled = choices.vendors.isNotEmpty(),
                    onClick = { choosing = Choosing.VENDOR },
                    trailing = chevron,
                )
            },
            {
                ListRow(
                    title = stringResource(R.string.setup_printer),
                    supporting = choices.printer ?: notChosen,
                    enabled = choices.printers.isNotEmpty(),
                    onClick = { choosing = Choosing.PRINTER },
                    trailing = chevron,
                )
            },
        ),
    )
    if (choices.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    choices.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    when (choosing) {
        Choosing.VENDOR -> ChoiceSheet(
            title = stringResource(R.string.setup_vendor),
            options = choices.vendors,
            selected = choices.vendor,
            onSelect = { vendor ->
                onIntent(SetupIntent.SelectVendor(vendor))
                choosing = Choosing.PRINTER
            },
            onDismiss = { if (choosing == Choosing.VENDOR) choosing = null },
            searchHint = stringResource(R.string.setup_search),
        )
        Choosing.PRINTER -> ChoiceSheet(
            title = stringResource(R.string.setup_printer),
            options = choices.printers,
            selected = choices.printer,
            onSelect = { onIntent(SetupIntent.SelectPrinter(it)) },
            onDismiss = { choosing = null },
            searchHint = stringResource(R.string.setup_search),
        )
        null -> Unit
    }
}

@Composable
private fun ColumnScope.FilamentsPage(state: SetupUiState, onIntent: (SetupIntent) -> Unit) {
    Text(stringResource(R.string.setup_filaments_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.setup_filaments_body, state.shown.size, state.filaments.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = state.filamentQuery,
        onValueChange = { onIntent(SetupIntent.SearchFilaments(it)) },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.setup_search)) },
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onIntent(SetupIntent.ShowAllFilaments(true)) }) { Text(stringResource(R.string.setup_filaments_all)) }
        TextButton(onClick = { onIntent(SetupIntent.ShowAllFilaments(false)) }) { Text(stringResource(R.string.setup_filaments_none)) }
    }
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(state.shownFilaments, key = { it }) { name ->
            val checked = name in state.shown
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value = checked, role = Role.Checkbox) { onIntent(SetupIntent.ToggleFilament(name)) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(name, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun ConnectionPage(host: HostForm, onIntent: (SetupIntent) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.setup_connection_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.setup_connection_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        ListGroup(items = PrintHostType.entries) { type ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = host.type == type, role = Role.RadioButton) { onIntent(SetupIntent.SetHostType(type)) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = host.type == type, onClick = null)
                Text(type.displayName, modifier = Modifier.padding(start = 16.dp))
            }
        }
        OutlinedTextField(
            value = host.address,
            onValueChange = { onIntent(SetupIntent.SetHostAddress(it)) },
            singleLine = true,
            label = { Text(stringResource(R.string.setup_connection_address)) },
            placeholder = { Text("192.168.1.50") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        if (host.type != PrintHostType.ELEGOO_LINK) {
            OutlinedTextField(
                value = host.apiKey,
                onValueChange = { onIntent(SetupIntent.SetHostApiKey(it)) },
                singleLine = true,
                label = { Text(stringResource(R.string.setup_connection_api_key)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TonalPillButton(
                stringResource(R.string.setup_connection_test),
                onClick = { onIntent(SetupIntent.TestHost) },
                enabled = host.config != null && host.test != HostTest.Testing,
            )
            when (val test = host.test) {
                HostTest.Idle -> Unit
                HostTest.Testing -> LinearProgressIndicator(Modifier.weight(1f))
                is HostTest.Passed -> Text(test.description, color = MaterialTheme.colorScheme.primary)
                is HostTest.Failed -> Text(test.message, color = MaterialTheme.colorScheme.error)
            }
        }
        Text(stringResource(R.string.setup_connection_later), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
