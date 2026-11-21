package com.orcamobile.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.OptionMode
import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.SettingsEdits
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val catalog: ConfigOptionCatalog,
    private val userSettings: UserSettingsRepository,
    private val printSettings: PrintSettingsRepository,
    private val layoutSource: SettingsLayoutSource,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var tabs = SettingsTabs(emptyMap())
    private var options: Map<String, ConfigOption> = emptyMap()
    private var presetValues: Map<String, String> = emptyMap()
    private var changes: Map<String, String> = emptyMap()

    init {
        viewModelScope.launch { load() }
    }

    fun onIntent(intent: SettingsIntent) {
        val presets = _state.value.presets
        when (intent) {
            is SettingsIntent.SelectKind -> refresh { copy(kind = intent.kind, pageIndex = 0) }
            is SettingsIntent.SelectPage -> _state.update { it.copy(pageIndex = intent.index.coerceIn(0, (it.pages.size - 1).coerceAtLeast(0))) }
            is SettingsIntent.SetMode -> refresh { copy(mode = intent.mode) }
            is SettingsIntent.Search -> refresh { copy(query = intent.query) }
            is SettingsIntent.Edit -> {
                val option = options[intent.key] ?: return
                if (SettingValues.problem(option, intent.edited) != null) return
                val written = SettingValues.write(option, currentValue(intent.key), intent.edited)
                viewModelScope.launch {
                    if (written == presetValues[intent.key]) {
                        printSettings.reset(presets, intent.key)
                    } else {
                        printSettings.set(presets, intent.key, written)
                    }
                }
            }
            is SettingsIntent.Reset -> viewModelScope.launch { printSettings.reset(presets, intent.key) }
            SettingsIntent.ResetAll -> viewModelScope.launch { printSettings.resetAll() }
        }
    }

    private suspend fun load() {
        val presets = userSettings.presetSelection()
        _state.update { it.copy(presets = presets) }
        val loaded = runCatching {
            val layout = withContext(dispatchers.io) { layoutSource.load() }
            val presetKeys = catalog.presetOptionKeys().getOrThrow()
            val allKeys = (layout.keys + presetKeys.process + presetKeys.filament + presetKeys.printer).distinct()
            val described = catalog.describe(allKeys).getOrThrow().associateBy { it.key }
            val completed = layout.completedWith(presetKeys, described, OTHER_PAGE)
            val values = catalog.values(presets, completed.keys).getOrThrow()
            Triple(completed, described, values)
        }
        loaded.onFailure { error ->
            _state.update { it.copy(loading = false, error = error.message ?: "Could not read the settings") }
            return
        }
        val (completed, described, values) = loaded.getOrThrow()
        tabs = completed
        options = described
        presetValues = values
        printSettings.edits.collect { edits -> onEdits(presets, edits) }
    }

    private fun onEdits(presets: PresetSelection?, edits: SettingsEdits) {
        changes = if (edits.appliesTo(presets)) edits.values else emptyMap()
        refresh { copy(loading = false, error = null) }
    }

    /** Applies [change] and rebuilds the shown pages and search results from it. */
    private fun refresh(change: SettingsUiState.() -> SettingsUiState) {
        _state.update { old ->
            val state = old.change()
            val pages = tabs.of(state.kind).mapNotNull { page -> pageUi(page, state.mode) }
            state.copy(
                pages = pages,
                pageIndex = state.pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
                results = if (state.searching) search(state.query, state.mode) else emptyList(),
                changedByKind = PresetKind.entries.associateWith { kind -> kindKeys(kind).count { it in changes } },
            )
        }
    }

    private fun pageUi(page: SettingsPage, mode: OptionMode): PageUi? {
        val groups = page.groups.mapNotNull { group -> groupUi(group.title, group.keys, mode) }
        return groups.takeIf { it.isNotEmpty() }?.let { PageUi(page.title, it) }
    }

    private fun groupUi(title: String, keys: List<String>, mode: OptionMode, path: String? = null): GroupUi? {
        val rows = keys.mapNotNull { key -> row(key, mode) }
        return rows.takeIf { it.isNotEmpty() }?.let { GroupUi(title, it, path) }
    }

    private fun row(key: String, mode: OptionMode): SettingRow? {
        val option = options[key] ?: return null
        val preset = presetValues[key] ?: return null
        if (option.mode == OptionMode.DEVELOP || option.mode.ordinal > mode.ordinal) return null
        return SettingRow(option, changes[key] ?: preset, preset)
    }

    /** Options whose label, explanation or key mention [query], grouped where the desktop shows them. */
    private fun search(query: String, mode: OptionMode): List<GroupUi> {
        val words = query.trim().lowercase().split(Regex("\\s+"))
        fun matches(key: String): Boolean {
            val option = options[key] ?: return false
            val text = "${option.label} ${option.fullLabel} ${option.key} ${option.tooltip}".lowercase()
            return words.all { it in text }
        }
        return PresetKind.entries.flatMap { kind ->
            tabs.of(kind).flatMap { page ->
                page.groups.mapNotNull { group ->
                    groupUi(group.title, group.keys.filter(::matches), mode, path = "${kindTitle(kind)} › ${page.title}")
                }
            }
        }
    }

    private fun kindKeys(kind: PresetKind): List<String> = tabs.of(kind).flatMap { page -> page.groups.flatMap { it.keys } }

    private fun currentValue(key: String): String = changes[key] ?: presetValues[key].orEmpty()

    private fun kindTitle(kind: PresetKind) = when (kind) {
        PresetKind.PROCESS -> "Process"
        PresetKind.FILAMENT -> "Filament"
        PresetKind.PRINTER -> "Printer"
    }

    private companion object {
        const val OTHER_PAGE = "Other"
    }
}
