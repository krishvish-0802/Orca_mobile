package com.orcamobile.core.domain.presets

import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.model.PresetChoices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's one preset selection, shared by every screen that shows it, so a
 * printer chosen on the plate is the one the start screen and the settings
 * show. Choices are remembered; picking a printer suggests its profile's
 * process and filament, as the desktop app does. Choices apply in the order
 * they were made.
 */
@Singleton
class PresetPicker @Inject constructor(
    private val catalog: PresetCatalog,
    private val settings: UserSettingsRepository,
) {
    private val _choices = MutableStateFlow(PresetChoices())
    val choices: StateFlow<PresetChoices> = _choices.asStateFlow()

    private val mutex = Mutex()
    private var loaded = false

    /** Loads the vendors and restores the saved selection, once. */
    suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        _choices.update { it.copy(loading = true, error = null) }
        val vendors = catalog.vendors().getOrElse { return@withLock fail(it) }
        _choices.update { it.copy(loading = false, vendors = vendors) }
        loaded = true
        val saved = settings.presetSelection() ?: return@withLock
        if (saved.vendor !in vendors) return@withLock
        val printers = catalog.printers(saved.vendor).getOrElse { return@withLock fail(it) }
        _choices.update { it.copy(vendor = saved.vendor, printers = printers) }
        if (saved.printer !in printers) return@withLock
        val compatible = catalog.compatiblePresets(saved.vendor, saved.printer).getOrElse { return@withLock fail(it) }
        val filament = saved.filaments.firstOrNull()
        _choices.update {
            it.copy(
                printer = saved.printer,
                processes = compatible.processes,
                process = saved.process.takeIf { p -> p in compatible.processes } ?: compatible.defaultProcess,
                filaments = compatible.filaments,
                filament = filament?.takeIf { f -> f in compatible.filaments } ?: compatible.defaultFilament,
            )
        }
    }

    suspend fun selectVendor(vendor: String) = mutex.withLock {
        _choices.update { PresetChoices(loading = true, vendors = it.vendors, vendor = vendor) }
        catalog.printers(vendor).fold(
            { printers -> _choices.update { it.copy(loading = false, printers = printers) } },
            { fail(it) },
        )
    }

    suspend fun selectPrinter(printer: String) = mutex.withLock {
        val vendor = _choices.value.vendor ?: return@withLock
        _choices.update {
            it.copy(loading = true, printer = printer, processes = emptyList(), process = null, filaments = emptyList(), filament = null, error = null)
        }
        catalog.compatiblePresets(vendor, printer).fold(
            { compatible ->
                _choices.update {
                    it.copy(
                        loading = false,
                        processes = compatible.processes,
                        process = compatible.defaultProcess,
                        filaments = compatible.filaments,
                        filament = compatible.defaultFilament,
                    )
                }
                remember()
            },
            { fail(it) },
        )
    }

    suspend fun selectProcess(process: String) = mutex.withLock {
        _choices.update { it.copy(process = process) }
        remember()
    }

    suspend fun selectFilament(filament: String) = mutex.withLock {
        _choices.update { it.copy(filament = filament) }
        remember()
    }

    private suspend fun remember() {
        _choices.value.selection?.let { settings.savePresetSelection(it) }
    }

    private fun fail(error: Throwable) {
        _choices.update { it.copy(loading = false, error = error.message ?: "Could not load presets") }
    }
}
