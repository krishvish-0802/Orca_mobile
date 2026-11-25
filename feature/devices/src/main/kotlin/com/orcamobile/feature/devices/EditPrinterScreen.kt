package com.orcamobile.feature.devices

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orcamobile.core.designsystem.component.DropdownField
import com.orcamobile.core.designsystem.component.ListGroup
import com.orcamobile.core.designsystem.component.ListRow
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.SectionHeader
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.model.PrintHostType

@Composable
fun EditPrinterRoute(onDone: () -> Unit, onRemoved: () -> Unit = onDone, viewModel: EditPrinterViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                EditPrinterEvent.Done -> onDone()
                EditPrinterEvent.Removed -> onRemoved()
            }
        }
    }
    EditPrinterScreen(state, viewModel::onIntent, onBack = onDone)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPrinterScreen(state: EditPrinterUiState, onIntent: (EditPrinterIntent) -> Unit, onBack: () -> Unit) {
    var confirmRemove by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.editing == null) R.string.devices_add_title else R.string.devices_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.devices_back)) }
                },
                actions = {
                    if (state.editing != null) TextButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.devices_remove)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.editing == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(stringResource(R.string.devices_found), modifier = Modifier.weight(1f))
                    TextButton(onClick = { onIntent(EditPrinterIntent.Scan) }, enabled = !state.scanning) {
                        Text(stringResource(R.string.devices_scan_again))
                    }
                }
                if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.found.isEmpty()) {
                    Text(
                        stringResource(if (state.scanning) R.string.devices_scanning else R.string.devices_found_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ListGroup(items = state.found) { printer ->
                        ListRow(
                            title = printer.name,
                            supporting = listOfNotNull(printer.model, printer.host.baseUrl).joinToString(" · "),
                            onClick = { onIntent(EditPrinterIntent.Use(printer)) },
                        )
                    }
                }
                SectionHeader(stringResource(R.string.devices_by_address))
            }
            OutlinedTextField(
                value = state.name,
                onValueChange = { onIntent(EditPrinterIntent.SetName(it)) },
                label = { Text(stringResource(R.string.devices_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownField(
                label = stringResource(R.string.devices_type),
                options = HOST_TYPES.map { it.displayName },
                selected = state.type.displayName,
                onSelect = { name -> HOST_TYPES.firstOrNull { it.displayName == name }?.let { onIntent(EditPrinterIntent.SetType(it)) } },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.address,
                onValueChange = { onIntent(EditPrinterIntent.SetAddress(it)) },
                label = { Text(stringResource(R.string.devices_address)) },
                placeholder = { Text("192.168.1.50") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.type != PrintHostType.ELEGOO_LINK) {
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = { onIntent(EditPrinterIntent.SetApiKey(it)) },
                    label = { Text(stringResource(R.string.devices_api_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TonalPillButton(
                    stringResource(R.string.devices_test),
                    onClick = { onIntent(EditPrinterIntent.Test) },
                    enabled = state.host != null && state.test != ConnectionTest.Testing,
                )
                when (val test = state.test) {
                    ConnectionTest.Idle -> Unit
                    ConnectionTest.Testing -> LinearProgressIndicator(Modifier.weight(1f))
                    is ConnectionTest.Passed -> Text(test.description, color = MaterialTheme.colorScheme.primary)
                    is ConnectionTest.Failed -> Text(test.message, color = MaterialTheme.colorScheme.error)
                }
            }
            PrimaryPillButton(
                stringResource(R.string.devices_save),
                onClick = { onIntent(EditPrinterIntent.Save) },
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.devices_remove_title, state.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onIntent(EditPrinterIntent.Remove)
                }) { Text(stringResource(R.string.devices_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.devices_cancel)) } },
        )
    }
}

/** The connections the app can make, in the order it offers them. */
private val HOST_TYPES = PrintHostType.entries.toList()
