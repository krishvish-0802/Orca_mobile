package com.orcamobile.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.domain.presets.ComparePresetsUseCase
import com.orcamobile.core.domain.presets.PresetPicker
import com.orcamobile.core.domain.presets.PresetSwitcher
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.OptionMode
import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.PresetOptionKeys
import com.orcamobile.core.model.SettingsReview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Every setting of the desktop's tabs, for the presets or (opened with
 * [SETTINGS_PROJECT_ARG] and friends) for one object, part or height range.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sources: SettingsSourceFactory,
    private val catalog: ConfigOptionCatalog,
    private val layoutSource: SettingsLayoutSource,
    private val dispatchers: CoroutineDispatchers,
    private val picker: PresetPicker,
    private val switcher: PresetSwitcher,
    private val comparePresets: ComparePresetsUseCase,
) : ViewModel() {

    private val args: SettingsArgs = settingsArgs(savedStateHandle)
    private var source: SettingsSource? = null
    private var allowed: Set<String>? = null

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var tabs = SettingsTabs(emptyMap())
    private var options: Map<String, ConfigOption> = emptyMap()
    private var presetValues: Map<String, String> = emptyMap()
    private var changes: Map<String, String> = emptyMap()
    private var review: SettingsReview = SettingsReview.NONE
    private var reviewJob: Job? = null
    private var loadJob: Job? = null
    private var presetKeys = PresetOptionKeys(emptyList(), emptyList(), emptyList())
    private var filamentKeys: Set<String> = emptySet()

    /** The filament slot the rows being built show. */
    private var shownSlot = 0

    /** Changes the rules made in a row: rules that keep changing the same thing stop being followed. */
    private var ruleRounds = 0

    init {
        loadJob = viewModelScope.launch { load() }
    }

    fun onIntent(intent: SettingsIntent) {
        val source = source ?: return
        when (intent) {
            is SettingsIntent.SelectKind -> refresh { copy(kind = intent.kind, pageIndex = 0) }
            is SettingsIntent.SelectPage -> _state.update { it.copy(pageIndex = intent.index.coerceIn(0, (it.pages.size - 1).coerceAtLeast(0))) }
            is SettingsIntent.SetMode -> refresh { copy(mode = intent.mode) }
            is SettingsIntent.Search -> refresh { copy(query = intent.query) }
            is SettingsIntent.Edit -> {
                val option = options[intent.key] ?: return
                if (SettingValues.problem(option, intent.edited) != null) return
                val written = SettingValues.write(option, currentValue(intent.key), intent.edited, slotOf(intent.key))
                change { if (written == presetValues[intent.key]) source.reset(intent.key) else source.set(intent.key, written) }
            }
            is SettingsIntent.Reset -> {
                val option = options[intent.key] ?: return
                val slot = slotOf(intent.key)
                val preset = presetValues[intent.key].orEmpty()
                // One filament's value back to its preset's; the other filaments' stay.
                val perFilament = intent.key in filamentKeys && option.type.isVector && _state.value.filamentSlots.size > 1
                val written = if (!perFilament) preset
                else SettingValues.write(option, currentValue(intent.key), SettingValues.editable(option, preset, slot), slot)
                change { if (written == preset) source.reset(intent.key) else source.set(intent.key, written) }
            }
            is SettingsIntent.SelectFilamentSlot -> {
                val active = source ?: return
                refresh {
                    val slot = intent.slot.coerceIn(0, (filamentSlots.size - 1).coerceAtLeast(0))
                    copy(filamentSlot = slot, saveTargets = saveTargets(active, slot))
                }
            }
            is SettingsIntent.SavePreset -> change { savePreset(intent.kind, intent.name) }
            is SettingsIntent.OpenComparison -> {
                val choices = picker.choices.value
                val names = when (intent.kind) {
                    PresetKind.PRINTER -> choices.printers
                    PresetKind.PROCESS -> choices.processes
                    PresetKind.FILAMENT -> choices.filaments
                }
                val first = choices.chosen(intent.kind, if (intent.kind == PresetKind.FILAMENT) _state.value.filamentSlot else 0)
                _state.update { it.copy(comparison = ComparisonUi(intent.kind, names, first, null)) }
            }
            is SettingsIntent.Compare -> compare(intent.first, intent.second)
            SettingsIntent.CloseComparison -> {
                compareJob?.cancel()
                _state.update { it.copy(comparison = null) }
            }
            is SettingsIntent.RenamePreset -> change {
                picker.renameUserPreset(intent.kind, intent.name, intent.newName).getOrThrow()
                carryOver(changes)
            }
            is SettingsIntent.DeletePreset -> change {
                picker.deleteUserPreset(intent.kind, intent.name).getOrThrow()
                carryOver(changes)
            }
            SettingsIntent.ResetAll -> change { source.resetAll() }
            SettingsIntent.DismissProblem -> _state.update { it.copy(problem = null) }
            is SettingsIntent.ApplyFix -> change { write(source, intent.fix.changes) }
            SettingsIntent.DismissNotices -> _state.update { it.copy(notices = emptyList()) }
        }
    }

    private var compareJob: Job? = null

    private fun compare(first: String?, second: String?) {
        val open = _state.value.comparison ?: return
        compareJob?.cancel()
        val picked = open.copy(first = first, second = second, differences = emptyList(), error = null)
        if (first == null || second == null) {
            _state.update { it.copy(comparison = picked) }
            return
        }
        _state.update { it.copy(comparison = picked.copy(loading = true)) }
        compareJob = viewModelScope.launch {
            val result = comparePresets(open.kind, first, second)
            _state.update { state ->
                val now = state.comparison?.takeIf { it.first == first && it.second == second } ?: return@update state
                state.copy(
                    comparison = now.copy(
                        loading = false,
                        differences = result.getOrDefault(emptyList()),
                        error = result.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName },
                    ),
                )
            }
        }
    }

    /** The filament slot a setting's row shows: the Filament tab's for a per-filament setting. */
    private fun slotOf(key: String): Int {
        val option = options[key] ?: return 0
        return if (key in filamentKeys && option.type.isVector) _state.value.filamentSlot else 0
    }

    /**
     * Saves the changes to [kind]'s settings (for a filament, the shown slot's
     * values) as the user's preset [name], chooses it, and keeps the other
     * changes over the new presets.
     */
    private suspend fun savePreset(kind: PresetKind, name: String) {
        val slot = if (kind == PresetKind.FILAMENT) _state.value.filamentSlot else 0
        val values = switcher.presetValues(kind, changes, slot)
        picker.saveUserPreset(kind, name, values, slot).getOrThrow()
        // A filament's changes stay for the other filaments; whatever now matches the presets goes.
        val kept = if (kind == PresetKind.FILAMENT && _state.value.filamentSlots.size > 1) changes else changes - values.keys
        carryOver(kept)
    }

    /** Keeps [kept] over the presets now chosen (dropping what they already give), and shows them. */
    private suspend fun carryOver(kept: Map<String, String>) {
        switcher.carryOver(kept)
        reload()
    }

    /** The screen again, from the presets now chosen. */
    private fun reload() {
        loadJob?.cancel()
        reviewJob?.cancel()
        loadJob = viewModelScope.launch { load() }
    }

    /** Writes [values] here: a value equal to the presets' is a reset, not a change. Settings this level can't take are left out. */
    private suspend fun write(source: SettingsSource, values: Map<String, String>) {
        for ((key, value) in values) {
            if (allowed?.contains(key) == false || options[key] == null) continue
            if (value == currentValue(key)) continue
            if (value == presetValues[key]) source.reset(key) else source.set(key, value)
        }
    }

    /**
     * The desktop's rules for the settings as they now are: what shows and can be
     * changed, conflicts to resolve, and values the rules set on their own,
     * which are written here (and reviewed again).
     */
    private fun reviewChanges(source: SettingsSource) {
        reviewJob?.cancel()
        reviewJob = viewModelScope.launch {
            val reviewed = catalog.review(source.presets, source.rulesBase() + changes, source.rulesScope).getOrNull() ?: return@launch
            review = reviewed
            val pending = reviewed.changes.filter { (key, value) ->
                (allowed?.contains(key) != false) && options[key] != null && value != currentValue(key)
            }
            _state.update { it.copy(checks = reviewed.checks, notices = (it.notices + reviewed.notices).distinct()) }
            refresh { this }
            if (pending.isEmpty()) {
                ruleRounds = 0
            } else if (ruleRounds++ < MAX_RULE_ROUNDS) {
                runCatching { write(source, pending) }
            }
        }
    }

    /** Runs a change; one the engine refuses is shown, not thrown. */
    private fun change(block: suspend () -> Unit) {
        _state.update { it.copy(problem = null) }
        viewModelScope.launch {
            runCatching { block() }.onFailure { error ->
                _state.update { it.copy(problem = error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    private suspend fun load() {
        val loaded = runCatching {
            picker.load()
            val source = sources.create(args)
            this.source = source
            allowed = source.allowedKeys()
            _state.update { it.copy(presets = source.presets, subject = source.subject, kinds = source.kinds, kind = source.kinds.first()) }
            val layout = withContext(dispatchers.io) { layoutSource.load() }
            val presetKeys = catalog.presetOptionKeys().getOrThrow()
            this.presetKeys = presetKeys
            filamentKeys = presetKeys.filament.toSet()
            val allKeys = (layout.keys + presetKeys.process + presetKeys.filament + presetKeys.printer).distinct()
            val described = catalog.describe(allKeys).getOrThrow().associateBy { it.key }
            val completed = layout.completedWith(presetKeys, described, OTHER_PAGE)
            val values = source.inherited(completed.keys)
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
        args.focus?.let(::focusOn)
        val active = checkNotNull(source)
        val choices = picker.choices.value
        _state.update {
            it.copy(
                filamentSlots = if (active.presets != null && active.kinds.contains(PresetKind.FILAMENT)) choices.slots.map { s -> s.preset } else emptyList(),
                filamentSlot = it.filamentSlot.coerceIn(0, (choices.slots.size - 1).coerceAtLeast(0)),
                userPresets = choices.userPresets,
            )
        }
        active.changes.collect { edits ->
            changes = edits
            refresh { copy(loading = false, error = null, saveTargets = saveTargets(active, filamentSlot)) }
            reviewChanges(active)
        }
    }

    /** Applies [change] and rebuilds the shown pages and search results from it. */
    private fun refresh(change: SettingsUiState.() -> SettingsUiState) {
        _state.update { old ->
            val state = old.change()
            shownSlot = state.filamentSlot
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
        if (allowed?.contains(key) == false) return null
        val option = options[key] ?: return null
        val preset = presetValues[key] ?: return null
        if (option.mode == OptionMode.DEVELOP || option.mode.ordinal > mode.ordinal) return null
        // Hidden by the desktop's rules for these settings (support options without supports...).
        val rule = review.stateOf(key)
        if (!rule.visible) return null
        return SettingRow(
            option,
            changes[key] ?: preset,
            preset,
            enabled = rule.enabled,
            reason = rule.reason.takeIf { !rule.enabled && it.isNotEmpty() },
            label = review.labels[key],
            slot = if (key in filamentKeys && option.type.isVector) shownSlot else 0,
        )
    }

    /** Options whose label, explanation or key mention [query], grouped where the desktop shows them. */
    private fun search(query: String, mode: OptionMode): List<GroupUi> {
        val words = query.trim().lowercase().split(Regex("\\s+"))
        fun matches(key: String): Boolean {
            val option = options[key] ?: return false
            val text = "${option.label} ${option.fullLabel} ${option.key} ${option.tooltip}".lowercase()
            return words.all { it in text }
        }
        return _state.value.kinds.flatMap { kind ->
            tabs.of(kind).flatMap { page ->
                page.groups.mapNotNull { group ->
                    groupUi(group.title, group.keys.filter(::matches), mode, path = "${kindTitle(kind)} › ${page.title}")
                }
            }
        }
    }

    /** Opens the tab and page that hold [key], showing every level of detail. */
    private fun focusOn(key: String) {
        val kind = _state.value.kinds.firstOrNull { k -> tabs.of(k).any { page -> page.groups.any { key in it.keys } } } ?: return
        refresh { copy(kind = kind, mode = OptionMode.EXPERT) }
        val page = _state.value.pages.indexOfFirst { p -> p.groups.any { g -> g.rows.any { it.option.key == key } } }
        _state.update { it.copy(pageIndex = page.coerceAtLeast(0), focus = key) }
    }

    /** What the changes can be saved as: each kind with changes, to the preset chosen for it. */
    private fun saveTargets(source: SettingsSource, filamentSlot: Int): List<SaveTarget> {
        if (source.presets == null || source.kinds.size < PresetKind.entries.size) return emptyList()
        val choices = picker.choices.value
        return PresetKind.entries.mapNotNull { kind ->
            val count = presetKeys.of(kind).count { it in changes }
            val slot = if (kind == PresetKind.FILAMENT) filamentSlot else 0
            val current = choices.chosen(kind, slot) ?: return@mapNotNull null
            SaveTarget(kind, current, choices.userPreset(kind, current) != null, count).takeIf { count > 0 }
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
        const val MAX_RULE_ROUNDS = 3
    }
}

/** Navigation arguments: open the settings of one object (and part or height range) of a loaded project. */
const val SETTINGS_PROJECT_ARG = "project"
const val SETTINGS_OBJECT_ARG = "object"
const val SETTINGS_VOLUME_ARG = "volume"
const val SETTINGS_RANGE_ARG = "range"

/** The setting (its key) the presets' settings open on. */
const val SETTINGS_FOCUS_ARG = "focus"

internal fun settingsArgs(handle: SavedStateHandle): SettingsArgs {
    val focus = handle.get<String>(SETTINGS_FOCUS_ARG)?.takeIf { it.isNotEmpty() }
    val project = handle.get<String>(SETTINGS_PROJECT_ARG)?.takeIf { it.isNotEmpty() } ?: return SettingsArgs.Presets(focus)
    val objectIndex = handle.get<Int>(SETTINGS_OBJECT_ARG)?.takeIf { it >= 0 } ?: return SettingsArgs.Presets(focus)
    val volume = handle.get<Int>(SETTINGS_VOLUME_ARG)?.takeIf { it >= 0 }
    val range = handle.get<Int>(SETTINGS_RANGE_ARG)?.takeIf { it >= 0 }
    return SettingsArgs.Object(
        com.orcamobile.core.model.ProjectId(project),
        objectIndex,
        com.orcamobile.core.model.SettingTarget(volume = volume, range = range?.takeIf { volume == null }),
    )
}
