package com.orcamobile.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orcamobile.core.designsystem.component.ListGroup
import com.orcamobile.core.designsystem.component.ListRow
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.SectionHeader
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.model.OptionType
import java.util.Locale

@Composable
fun SettingsRoute(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(state, viewModel::onIntent, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit, onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.settings_title))
                        Text(
                            state.presets?.let { "${it.process} · ${it.filaments.joinToString()}" }
                                ?: stringResource(R.string.settings_defaults_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                actions = {
                    if (state.changedCount > 0) {
                        TextButton(onClick = { onIntent(SettingsIntent.ResetAll) }) { Text(stringResource(R.string.settings_reset_all)) }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> Text(
                state.error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(padding).padding(24.dp),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.changedCount > 0) {
                    item {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                            Text(
                                stringResource(R.string.settings_changed_count, state.changedCount),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                }
                items(state.groups, key = { it.title }) { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(stringResource(group.title))
                        ListGroup(items = group.rows) { row -> SettingRowItem(row, onIntent, onOpen = { editing = row.option.key }) }
                    }
                }
            }
        }
    }

    val open = editing?.let { key -> state.groups.flatMap { it.rows }.firstOrNull { it.option.key == key } }
    if (open != null) {
        SettingSheet(
            row = open,
            onIntent = onIntent,
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SettingRowItem(row: SettingRow, onIntent: (SettingsIntent) -> Unit, onOpen: () -> Unit) {
    val option = row.option
    val isBool = option.type == OptionType.BOOL || option.type == OptionType.BOOLS
    val supporting = if (row.changed) {
        stringResource(R.string.settings_changed, displayValue(row.presetValue, row))
    } else {
        null
    }
    ListRow(
        title = option.label,
        supporting = supporting,
        onClick = if (isBool) {
            { onIntent(SettingsIntent.Edit(option.key, if (checked(row)) "0" else "1")) }
        } else {
            onOpen
        },
        trailing = {
            if (row.changed) {
                IconButton(onClick = { onIntent(SettingsIntent.Reset(option.key)) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.settings_reset), modifier = Modifier.size(20.dp))
                }
            }
            if (isBool) {
                Switch(checked = checked(row), onCheckedChange = { onIntent(SettingsIntent.Edit(option.key, if (it) "1" else "0")) })
            } else {
                Text(
                    displayValue(row.value, row),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (row.changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
    )
}

private fun checked(row: SettingRow) = SettingValues.editable(row.option, row.value) == "1"

@Composable
private fun displayValue(serialized: String, row: SettingRow): String {
    if (row.option.type == OptionType.BOOL || row.option.type == OptionType.BOOLS) {
        return stringResource(if (SettingValues.editable(row.option, serialized) == "1") R.string.settings_on else R.string.settings_off)
    }
    return SettingValues.display(row.option, serialized)
}

/** Edits one option: its choices, or a value with its unit and limits, with the option's explanation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingSheet(row: SettingRow, onIntent: (SettingsIntent) -> Unit, onDismiss: () -> Unit) {
    val option = row.option
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(option.fullLabel, style = MaterialTheme.typography.headlineSmall)
            if (option.tooltip.isNotBlank()) {
                Text(option.tooltip, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (option.choices.isNotEmpty()) {
                ChoiceList(row) { value ->
                    onIntent(SettingsIntent.Edit(option.key, value))
                    onDismiss()
                }
            } else {
                ValueEditor(row, onIntent, onDismiss)
            }
        }
    }
}

@Composable
private fun ChoiceList(row: SettingRow, onChoose: (String) -> Unit) {
    val current = SettingValues.editable(row.option, row.value)
    val preset = SettingValues.editable(row.option, row.presetValue)
    ListGroup(items = row.option.choices) { choice ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(selected = choice.value == current, role = Role.RadioButton) { onChoose(choice.value) }
                .padding(horizontal = 16.dp),
        ) {
            RadioButton(selected = choice.value == current, onClick = null)
            Text(choice.label, modifier = Modifier.padding(start = 16.dp).weight(1f))
            if (choice.value == preset && preset != current) {
                Text(
                    stringResource(R.string.settings_preset_choice),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ValueEditor(row: SettingRow, onIntent: (SettingsIntent) -> Unit, onDismiss: () -> Unit) {
    val option = row.option
    var text by remember(option.key) { mutableStateOf(SettingValues.editable(option, row.value)) }
    val problem = SettingValues.problem(option, text)
    val keyboard = when (option.type) {
        OptionType.INT, OptionType.INTS -> KeyboardType.Number
        OptionType.STRING, OptionType.STRINGS, OptionType.FLOAT_OR_PERCENT, OptionType.FLOATS_OR_PERCENTS -> KeyboardType.Text
        else -> KeyboardType.Decimal
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        isError = problem != null,
        suffix = option.unit.takeIf { it.isNotBlank() }?.let { unit -> { Text(unit) } },
        supportingText = problem?.let { { Text(problemText(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        if (row.changed) {
            TonalPillButton(
                text = stringResource(R.string.settings_reset),
                onClick = {
                    onIntent(SettingsIntent.Reset(option.key))
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        PrimaryPillButton(
            text = stringResource(R.string.settings_done),
            enabled = problem == null,
            onClick = {
                onIntent(SettingsIntent.Edit(option.key, text))
                onDismiss()
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun problemText(problem: SettingValues.Problem): String = when (problem) {
    SettingValues.Problem.Empty -> stringResource(R.string.settings_problem_empty)
    SettingValues.Problem.NotANumber -> stringResource(R.string.settings_problem_number)
    SettingValues.Problem.NotAWholeNumber -> stringResource(R.string.settings_problem_whole_number)
    is SettingValues.Problem.BelowMin -> stringResource(R.string.settings_problem_min, format(problem.min))
    is SettingValues.Problem.AboveMax -> stringResource(R.string.settings_problem_max, format(problem.max))
}

private fun format(value: Double): String =
    if (value == Math.rint(value)) value.toLong().toString() else String.format(Locale.ROOT, "%.3f", value).trimEnd('0')

