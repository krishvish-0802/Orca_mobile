package com.orcamobile.feature.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orcamobile.core.model.HeaterKind

@Composable
fun PrintersRoute(
    onBack: () -> Unit,
    onOpenPrinter: (String) -> Unit,
    onAddPrinter: () -> Unit,
    viewModel: PrintersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PrintersScreen(state, viewModel::onIntent, onBack, onOpenPrinter, onAddPrinter)
}

/** The Device tab: every printer, what it's doing, and the one prints go to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintersScreen(
    state: PrintersUiState,
    onIntent: (PrintersIntent) -> Unit,
    onBack: () -> Unit,
    onOpenPrinter: (String) -> Unit,
    onAddPrinter: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.devices_back)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddPrinter,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.devices_add)) },
            )
        },
    ) { padding ->
        when {
            state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(padding))
            state.printers.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.devices_empty_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.devices_empty_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.printers, key = { it.printer.id }) { card ->
                    PrinterCardView(card, onOpen = { onOpenPrinter(card.printer.id) }, onSelect = { onIntent(PrintersIntent.Select(card.printer.id)) })
                }
            }
        }
    }
}

@Composable
private fun PrinterCardView(card: PrinterCard, onOpen: () -> Unit, onSelect: () -> Unit) {
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.printer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${card.printer.host.type.displayName} · ${card.printer.host.baseUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RadioButton(selected = card.selected, onClick = onSelect)
            }
            val printerState = card.state
            if (printerState == null) {
                Text(stringResource(R.string.devices_upload_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(statusText(printerState), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            printerState.job?.let { job ->
                Text(job.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                job.progress?.let { LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth()) }
                job.remainingSeconds?.takeIf { printerState.machine.active }?.let {
                    Text(stringResource(R.string.devices_left, duration(it)), style = MaterialTheme.typography.bodySmall)
                }
            }
            val temps = printerState.heaters.filter { it.kind != HeaterKind.CHAMBER || it.current > 0 }
            if (temps.isNotEmpty() && printerState.online) {
                Text(
                    temps.map { "${heaterName(it.kind)} ${it.current.toInt()}°" }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
