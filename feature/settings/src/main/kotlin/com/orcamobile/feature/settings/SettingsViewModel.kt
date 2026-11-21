package com.orcamobile.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.SettingsEdits
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val catalog: ConfigOptionCatalog,
    private val userSettings: UserSettingsRepository,
    private val printSettings: PrintSettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var options: Map<String, ConfigOption> = emptyMap()
    private var presetValues: Map<String, String> = emptyMap()

    init {
        viewModelScope.launch { load() }
    }

    fun onIntent(intent: SettingsIntent) {
        val presets = _state.value.presets
        when (intent) {
            is SettingsIntent.Edit -> {
                val option = options[intent.key] ?: return
                if (SettingValues.problem(option, intent.edited) != null) return
                val base = currentValue(intent.key)
                val written = SettingValues.write(option, base, intent.edited)
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
        val described = catalog.describe(SettingsLayout.keys)
        val values = catalog.values(presets, SettingsLayout.keys)
        val error = described.exceptionOrNull() ?: values.exceptionOrNull()
        if (error != null) {
            _state.update { it.copy(loading = false, presets = presets, error = error.message ?: "Could not read the settings") }
            return
        }
        options = described.getOrThrow().associateBy { it.key }
        presetValues = values.getOrThrow()
        _state.update { it.copy(presets = presets) }
        printSettings.edits.collect { edits -> show(presets, edits) }
    }

    private fun show(presets: PresetSelection?, edits: SettingsEdits) {
        val changes = if (edits.appliesTo(presets)) edits.values else emptyMap()
        val groups = SettingsLayout.groups.mapNotNull { group ->
            val rows = group.keys.mapNotNull { key ->
                val option = options[key] ?: return@mapNotNull null
                val preset = presetValues[key] ?: return@mapNotNull null
                SettingRow(option, changes[key] ?: preset, preset)
            }
            rows.takeIf { it.isNotEmpty() }?.let { SettingsGroupUi(group.title, it) }
        }
        _state.update { it.copy(loading = false, error = null, groups = groups) }
    }

    private fun currentValue(key: String): String =
        _state.value.groups.firstNotNullOfOrNull { group -> group.rows.firstOrNull { it.option.key == key }?.value }
            ?: presetValues[key].orEmpty()
}
