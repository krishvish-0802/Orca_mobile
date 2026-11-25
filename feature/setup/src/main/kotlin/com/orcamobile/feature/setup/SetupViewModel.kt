package com.orcamobile.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.domain.presets.PresetChange
import com.orcamobile.core.domain.presets.PresetPicker
import com.orcamobile.core.domain.presets.PresetSwitcher
import com.orcamobile.core.domain.usecase.TestPrintHostUseCase
import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.PrintHostType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The first-run setup, as the desktop's Setup Wizard: the printer, the
 * filaments its lists show, and (optionally) how the phone reaches it.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val picker: PresetPicker,
    private val switcher: PresetSwitcher,
    private val catalog: PresetCatalog,
    private val settings: UserSettingsRepository,
    private val testHost: TestPrintHostUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private val _events = Channel<SetupEvent>(Channel.BUFFERED)
    val events: Flow<SetupEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch { picker.load() }
        viewModelScope.launch { picker.choices.collect { choices -> _state.update { it.copy(choices = choices) } } }
    }

    fun onIntent(intent: SetupIntent) {
        when (intent) {
            is SetupIntent.SelectVendor -> viewModelScope.launch { picker.selectVendor(intent.vendor) }
            // Any changed settings stay: the setup is no place to lose them.
            is SetupIntent.SelectPrinter -> viewModelScope.launch { switcher.switch(PresetChange.Printer(intent.printer)) }
            is SetupIntent.ToggleFilament -> _state.update {
                it.copy(shown = if (intent.name in it.shown) it.shown - intent.name else it.shown + intent.name)
            }
            is SetupIntent.ShowAllFilaments -> _state.update { it.copy(shown = if (intent.all) it.filaments.toSet() else emptySet()) }
            is SetupIntent.SearchFilaments -> _state.update { it.copy(filamentQuery = intent.query) }
            is SetupIntent.SetHostType -> updateHost { copy(type = intent.type, test = HostTest.Idle) }
            is SetupIntent.SetHostAddress -> updateHost { copy(address = intent.address, test = HostTest.Idle) }
            is SetupIntent.SetHostApiKey -> updateHost { copy(apiKey = intent.apiKey, test = HostTest.Idle) }
            SetupIntent.TestHost -> test()
            SetupIntent.Next -> next()
            SetupIntent.Back -> _state.update { it.copy(step = SetupStep.entries[(it.step.ordinal - 1).coerceAtLeast(0)], error = null) }
            SetupIntent.Skip -> viewModelScope.launch {
                settings.saveSetupDone()
                _events.send(SetupEvent.Finished)
            }
        }
    }

    private fun next() {
        val state = _state.value
        if (!state.canGoOn) return
        viewModelScope.launch {
            when (state.step) {
                SetupStep.PRINTER -> openFilaments()
                SetupStep.FILAMENTS -> openConnection()
                SetupStep.CONNECTION -> finish()
            }
        }
    }

    /** The filaments the printer suits, with those shown before (or a starting few) ticked. */
    private suspend fun openFilaments() {
        val choices = _state.value.choices
        val vendor = choices.vendor ?: return
        val printer = choices.printer ?: return
        val compatible = catalog.compatiblePresets(vendor, printer).getOrElse { e ->
            _state.update { it.copy(error = e.message) }
            return
        }
        val users = choices.userNames(PresetKind.FILAMENT)
        val all = compatible.filaments.filterNot { it in users }
        val shown = settings.shownFilaments(printer).filter { it in all }.toSet().ifEmpty { suggested(all, compatible.defaultFilament, vendor) }
        _state.update { it.copy(step = SetupStep.FILAMENTS, filaments = all, shown = shown, filamentQuery = "", error = null) }
    }

    private suspend fun openConnection() {
        val saved = settings.printHost()
        val vendor = _state.value.choices.vendor
        val host = saved?.let { HostForm(it.type, it.baseUrl, it.apiKey.orEmpty()) }
            ?: HostForm(type = if (vendor == ELEGOO) PrintHostType.ELEGOO_LINK else PrintHostType.MOONRAKER)
        _state.update { it.copy(step = SetupStep.CONNECTION, host = host, error = null) }
    }

    private suspend fun finish() {
        val state = _state.value
        val printer = state.choices.printer ?: return
        // Every filament ticked is the same as no choice: newer presets show up too.
        picker.setShownFilaments(printer, if (state.shown.containsAll(state.filaments)) emptySet() else state.shown)
        state.host.config?.let { settings.savePrintHost(it) }
        settings.saveSetupDone()
        _events.send(SetupEvent.Finished)
    }

    private fun test() {
        val config = _state.value.host.config ?: return
        updateHost { copy(test = HostTest.Testing) }
        viewModelScope.launch {
            val result = testHost(config)
            updateHost {
                if (this.config != config) {
                    this
                } else {
                    copy(test = result.fold({ HostTest.Passed(it.description) }, { HostTest.Failed(it.message ?: it.javaClass.simpleName) }))
                }
            }
        }
    }

    private fun updateHost(change: HostForm.() -> HostForm) = _state.update { it.copy(host = it.host.change()) }

    companion object {
        private const val ELEGOO = "Elegoo"

        /**
         * A starting choice, as the desktop ticks the printer's own: its default
         * filament, its maker's, and the generic ones. All of them when none match.
         */
        internal fun suggested(all: List<String>, default: String?, vendor: String): Set<String> {
            val picked = all.filter { it == default || it.contains(vendor, ignoreCase = true) || it.contains("Generic", ignoreCase = true) }
            return picked.ifEmpty { all }.toSet()
        }
    }
}
