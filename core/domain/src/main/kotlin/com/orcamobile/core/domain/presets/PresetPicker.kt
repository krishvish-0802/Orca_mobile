package com.orcamobile.core.domain.presets

import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.model.FilamentSlot
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
 * process and filament, as the desktop app does. Several filaments may be
 * loaded (filament 1, 2, ...), each with its own colour over its preset's.
 * Choices apply in the order they were made.
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
        // Saved filaments that no longer suit the printer give way to its default.
        val fallback = compatible.defaultFilament ?: compatible.filaments.firstOrNull()
        val slots = saved.filaments.mapIndexedNotNull { i, name ->
            val preset = name.takeIf { it in compatible.filaments } ?: fallback ?: return@mapIndexedNotNull null
            FilamentSlot(preset, saved.filamentColours.getOrNull(i)?.takeIf { it.isNotEmpty() })
        }.ifEmpty { listOfNotNull(fallback?.let(::FilamentSlot)) }
        _choices.update {
            it.copy(
                printer = saved.printer,
                processes = compatible.processes,
                process = saved.process.takeIf { p -> p in compatible.processes } ?: compatible.defaultProcess,
                filaments = compatible.filaments,
                slots = slots,
            )
        }
        readColours()
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
            it.copy(
                loading = true, printer = printer, processes = emptyList(), process = null, filaments = emptyList(),
                slots = emptyList(), slotColours = emptyList(), error = null,
            )
        }
        catalog.compatiblePresets(vendor, printer).fold(
            { compatible ->
                _choices.update {
                    it.copy(
                        loading = false,
                        processes = compatible.processes,
                        process = compatible.defaultProcess,
                        filaments = compatible.filaments,
                        slots = listOfNotNull((compatible.defaultFilament ?: compatible.filaments.firstOrNull())?.let(::FilamentSlot)),
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

    /** The first filament, for screens that show one. */
    suspend fun selectFilament(filament: String) = selectFilament(0, filament)

    /** Loads [filament] as filament [slot] + 1; its colour follows the new preset. */
    suspend fun selectFilament(slot: Int, filament: String) = mutex.withLock {
        _choices.update { choices ->
            val slots = choices.slots.ifEmpty { listOf(FilamentSlot(filament)) }
            if (slot !in slots.indices) return@update choices
            choices.copy(slots = slots.toMutableList().also { it[slot] = FilamentSlot(filament) })
        }
        remember()
    }

    /** Adds a filament after the last, a copy of it, as the desktop's "+" does. */
    suspend fun addFilament() = mutex.withLock {
        val choices = _choices.value
        val last = choices.slots.lastOrNull() ?: return@withLock
        if (choices.slots.size >= PresetChoices.MAX_FILAMENTS) return@withLock
        _choices.update { it.copy(slots = it.slots + last.copy(colour = null)) }
        remember()
    }

    /** Takes filament [slot] + 1 away; the later ones move up. The last one stays. */
    suspend fun removeFilament(slot: Int) = mutex.withLock {
        val choices = _choices.value
        if (choices.slots.size <= 1 || slot !in choices.slots.indices) return@withLock
        _choices.update { it.copy(slots = it.slots.filterIndexed { i, _ -> i != slot }) }
        remember()
    }

    /** Filament [slot] + 1 prints in [colour] ("#RRGGBB"), or its preset's colour when null. */
    suspend fun setFilamentColour(slot: Int, colour: String?) = mutex.withLock {
        if (slot !in _choices.value.slots.indices) return@withLock
        _choices.update { it.copy(slots = it.slots.toMutableList().also { s -> s[slot] = s[slot].copy(colour = colour) }) }
        remember()
    }

    private suspend fun remember() {
        _choices.value.selection?.let { settings.savePresetSelection(it) }
        readColours()
    }

    /** Each slot's colour as it will print, from its own or its preset's. */
    private suspend fun readColours() {
        val selection = _choices.value.selection ?: return
        catalog.filamentColours(selection).onSuccess { colours ->
            _choices.update { if (it.selection == selection) it.copy(slotColours = colours) else it }
        }
    }

    private fun fail(error: Throwable) {
        _choices.update { it.copy(loading = false, error = error.message ?: "Could not load presets") }
    }
}
