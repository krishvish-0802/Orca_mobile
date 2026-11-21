package com.orcamobile.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
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
import com.orcamobile.core.model.OptionMode
import com.orcamobile.core.model.OptionType
import com.orcamobile.core.model.PresetKind
import java.util.Locale

@Composable
fun SettingsRoute(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(state, viewModel::onIntent, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit, onBack: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (searchOpen) {
                            TextField(
                                value = state.query,
                                onValueChange = { onIntent(SettingsIntent.Search(it)) },
                                placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Column {
                                Text(stringResource(R.string.settings_title))
                                Text(
                                    state.presets?.let { "${it.printer} · ${it.process}" } ?: stringResource(R.string.settings_defaults_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                        }
                    },
                    actions = {
                        if (searchOpen) {
                            IconButton(onClick = {
                                searchOpen = false
                                onIntent(SettingsIntent.Search(""))
                            }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_search_close)) }
                        } else {
                            IconButton(onClick = { searchOpen = true }) {
                                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.settings_search))
                            }
                            if (state.changedCount > 0) {
                                TextButton(onClick = { onIntent(SettingsIntent.ResetAll) }) { Text(stringResource(R.string.settings_reset_all)) }
                            }
                        }
                    },
                )
                if (!state.searching && !state.loading && state.error == null) TabsHeader(state, onIntent)
            }
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
            else -> {
                val groups = if (state.searching) state.results else state.page?.groups.orEmpty()
                val listState = rememberLazyListState()
                LaunchedEffect(state.kind, state.pageIndex, state.searching) { listState.scrollToItem(0) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.searching && groups.isEmpty()) {
                        item { Text(stringResource(R.string.settings_search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(groups, key = { "${it.path}/${it.title}" }) { group ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column {
                                group.path?.let {
                                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                SectionHeader(group.title)
                            }
                            ListGroup(items = group.rows) { row -> SettingRowItem(row, onIntent, onOpen = { editing = row.option.key }) }
                        }
                    }
                }
            }
        }
    }

    val open = editing?.let { key -> (state.pages.flatMap { it.groups } + state.results).flatMap { it.rows }.firstOrNull { it.option.key == key } }
    if (open != null) SettingSheet(row = open, onIntent = onIntent, onDismiss = { editing = null })
}

/** Process / Filament / Printer, the pages of the chosen one, and the level of detail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsHeader(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            PresetKind.entries.forEachIndexed { index, kind ->
                val changed = state.changedByKind[kind] ?: 0
                SegmentedButton(
                    selected = state.kind == kind,
                    onClick = { onIntent(SettingsIntent.SelectKind(kind)) },
                    shape = SegmentedButtonDefaults.itemShape(index, PresetKind.entries.size),
                ) {
                    Text(stringResource(kindLabel(kind)) + if (changed > 0) " · $changed" else "", maxLines = 1)
                }
            }
        }
        if (state.pages.isNotEmpty()) {
            ScrollableTabRow(
                selectedTabIndex = state.pageIndex,
                edgePadding = 16.dp,
                containerColor = Color.Transparent,
                divider = {},
            ) {
                state.pages.forEachIndexed { index, page ->
                    Tab(
                        selected = index == state.pageIndex,
                        onClick = { onIntent(SettingsIntent.SelectPage(index)) },
                        text = { Text(page.title, maxLines = 1) },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_show), style = MaterialTheme.typography.labelLarge)
            val modes = listOf(OptionMode.SIMPLE, OptionMode.ADVANCED, OptionMode.EXPERT)
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { onIntent(SettingsIntent.SetMode(mode)) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        icon = {},
                    ) { Text(stringResource(modeLabel(mode)), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun SettingRowItem(row: SettingRow, onIntent: (SettingsIntent) -> Unit, onOpen: () -> Unit) {
    val option = row.option
    val isSwitch = option.type == OptionType.BOOL || option.type == OptionType.BOOLS
    val supporting = if (row.changed) stringResource(R.string.settings_changed, displayValue(row.presetValue, row)) else null
    ListRow(
        title = option.label,
        supporting = supporting,
        onClick = if (isSwitch && !SettingValues.isUnset(option, row.value)) {
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
            if (isSwitch && !SettingValues.isUnset(option, row.value)) {
                Switch(checked = checked(row), onCheckedChange = { onIntent(SettingsIntent.Edit(option.key, if (it) "1" else "0")) })
            } else {
                Text(
                    displayValue(row.value, row),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (row.changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).widthIn(max = 160.dp),
                )
            }
        },
    )
}

private fun checked(row: SettingRow) = SettingValues.editable(row.option, row.value) == "1"

@Composable
private fun displayValue(serialized: String, row: SettingRow): String {
    val option = row.option
    if (SettingValues.isUnset(option, serialized)) return stringResource(R.string.settings_unset)
    if (option.type == OptionType.BOOL || option.type == OptionType.BOOLS) {
        return stringResource(if (SettingValues.editable(option, serialized) == "1") R.string.settings_on else R.string.settings_off)
    }
    val text = SettingValues.display(option, serialized)
    return text.ifEmpty { stringResource(R.string.settings_empty) }
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
                .imePadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(option.fullLabel, style = MaterialTheme.typography.headlineSmall)
            if (option.tooltip.isNotBlank()) {
                Text(option.tooltip, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(option.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            when {
                option.type == OptionType.BOOL || option.type == OptionType.BOOLS -> SwitchEditor(row, onIntent, onDismiss)
                option.choices.isNotEmpty() -> ChoiceList(row) { value ->
                    onIntent(SettingsIntent.Edit(option.key, value))
                    onDismiss()
                }
                else -> ValueEditor(row, onIntent, onDismiss)
            }
        }
    }
}

@Composable
private fun SwitchEditor(row: SettingRow, onIntent: (SettingsIntent) -> Unit, onDismiss: () -> Unit) {
    val choices = listOf("1" to stringResource(R.string.settings_on), "0" to stringResource(R.string.settings_off))
    val current = if (SettingValues.isUnset(row.option, row.value)) SettingValues.UNSET else SettingValues.editable(row.option, row.value)
    ListGroup(items = choices + if (row.option.nullable) listOf(SettingValues.UNSET to stringResource(R.string.settings_unset)) else emptyList()) { (value, label) ->
        RadioRow(label, selected = value == current) {
            onIntent(SettingsIntent.Edit(row.option.key, value))
            onDismiss()
        }
    }
}

@Composable
private fun ChoiceList(row: SettingRow, onChoose: (String) -> Unit) {
    val current = SettingValues.editable(row.option, row.value)
    val preset = SettingValues.editable(row.option, row.presetValue)
    ListGroup(items = row.option.choices) { choice ->
        RadioRow(choice.label, selected = choice.value == current, note = stringResource(R.string.settings_preset_choice).takeIf { choice.value == preset && preset != current }) {
            onChoose(choice.value)
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, note: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 16.dp).weight(1f))
        if (note != null) {
            Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ValueEditor(row: SettingRow, onIntent: (SettingsIntent) -> Unit, onDismiss: () -> Unit) {
    val option = row.option
    val unset = SettingValues.isUnset(option, row.value)
    var text by remember(option.key) { mutableStateOf(if (unset) "" else SettingValues.editable(option, row.value)) }
    val problem = SettingValues.problem(option, text)
    val multiline = option.multiline || text.contains('\n')
    val keyboard = when (option.type) {
        OptionType.INT, OptionType.INTS -> KeyboardType.Number
        OptionType.FLOAT, OptionType.FLOATS, OptionType.PERCENT, OptionType.PERCENTS -> KeyboardType.Decimal
        else -> KeyboardType.Text
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = !multiline,
        minLines = if (multiline) 8 else 1,
        isError = problem != null,
        suffix = option.unit.takeIf { it.isNotBlank() && !multiline }?.let { unit -> { Text(unit) } },
        supportingText = problem?.let { { Text(problemText(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        textStyle = if (multiline) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    if (option.nullable) {
        TextButton(onClick = {
            onIntent(SettingsIntent.Edit(option.key, SettingValues.UNSET))
            onDismiss()
        }) { Text(stringResource(R.string.settings_leave_unset)) }
    }
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

private fun kindLabel(kind: PresetKind) = when (kind) {
    PresetKind.PROCESS -> R.string.settings_tab_process
    PresetKind.FILAMENT -> R.string.settings_tab_filament
    PresetKind.PRINTER -> R.string.settings_tab_printer
}

private fun modeLabel(mode: OptionMode) = when (mode) {
    OptionMode.SIMPLE -> R.string.settings_mode_simple
    OptionMode.ADVANCED -> R.string.settings_mode_advanced
    else -> R.string.settings_mode_expert
}
