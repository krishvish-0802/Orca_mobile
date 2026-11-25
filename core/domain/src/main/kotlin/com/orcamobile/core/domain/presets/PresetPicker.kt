package com.orcamobile.core.domain.presets

import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.domain.model.UserSettingsRepository
import com.orcamobile.core.model.FilamentSlot
import com.orcamobile.core.model.PresetChoices
import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.UserPreset
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
 * Choices apply in the order they were made. The user's own presets are kept
 * here too: saved, renamed and deleted, and given to the engine, which lists
 * them with the system presets they inherit.
 */
@Singleton
class PresetPicker @Inject constructor(
    private val catalog: PresetCatalog,
    private val settings: UserSettingsRepository,
    private val userPresets: UserPresetRepository = InMemoryUserPresets(),
) {
    private val _choices = MutableStateFlow(PresetChoices())
    val choices: StateFlow<PresetChoices> = _choices.asStateFlow()

    private val mutex = Mutex()
    private var loaded = false

    /** Loads the vendors and restores the saved selection, once. */
    suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        _choices.update { it.copy(loading = true, error = null) }
        val users = userPresets.all()
        catalog.setUserPresets(users)
        _choices.update { it.copy(userPresets = users) }
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
        val listed = listed(saved.printer, compatible.filaments, slots)
        _choices.update {
            it.copy(
                printer = saved.printer,
                processes = compatible.processes,
                process = saved.process.takeIf { p -> p in compatible.processes } ?: compatible.defaultProcess,
                filaments = listed,
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
                val listed = listed(printer, compatible.filaments, emptyList())
                val first = compatible.defaultFilament?.takeIf { it in listed } ?: listed.firstOrNull()
                _choices.update {
                    it.copy(
                        loading = false,
                        processes = compatible.processes,
                        process = compatible.defaultProcess,
                        filaments = listed,
                        slots = listOfNotNull(first?.let(::FilamentSlot)),
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

    /**
     * Chooses [selection] whole (a project's presets): its printer, process and
     * filaments with their colours. Fails when any of them isn't here.
     */
    suspend fun choose(selection: PresetSelection): Result<Unit> = mutex.withLock {
        runCatching {
            val vendors = _choices.value.vendors.ifEmpty { catalog.vendors().getOrThrow() }
            require(selection.vendor in vendors) { "${selection.vendor}'s presets aren't here" }
            val printers = catalog.printers(selection.vendor).getOrThrow()
            require(selection.printer in printers) { "The printer ${selection.printer} isn't here" }
            val compatible = catalog.compatiblePresets(selection.vendor, selection.printer).getOrThrow()
            require(selection.process in compatible.processes) { "The process ${selection.process} doesn't suit this printer" }
            selection.filaments.firstOrNull { it !in compatible.filaments }?.let { throw IllegalArgumentException("The filament $it doesn't suit this printer") }
            val slots = selection.filaments.mapIndexed { i, name -> FilamentSlot(name, selection.filamentColours.getOrNull(i)?.ifEmpty { null }) }
            val listed = listed(selection.printer, compatible.filaments, slots)
            _choices.update {
                it.copy(
                    loading = false, error = null, vendors = vendors, vendor = selection.vendor, printers = printers, printer = selection.printer,
                    processes = compatible.processes, process = selection.process, filaments = listed, slots = slots,
                )
            }
            remember()
        }
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

    /**
     * Saves [values] (serialized as in profiles) over the chosen preset of [kind]
     * (for a filament, slot [slot]'s) as the user's preset [name], and chooses it.
     * Saving over one of the user's presets keeps its other values; one of
     * their presets saved under its own name is overwritten.
     */
    suspend fun saveUserPreset(kind: PresetKind, name: String, values: Map<String, String>, slot: Int = 0): Result<Unit> = mutex.withLock {
        val choices = _choices.value
        val trimmed = name.trim()
        val current = choices.chosen(kind, slot) ?: return@withLock Result.failure(IllegalStateException("Choose a preset first"))
        val currentUser = choices.userPreset(kind, current)
        val inherits = currentUser?.inherits ?: current
        when {
            trimmed.isEmpty() -> return@withLock Result.failure(IllegalArgumentException("Give the preset a name"))
            choices.userPreset(kind, trimmed) == null && trimmed in systemNames(choices, kind) ->
                return@withLock Result.failure(IllegalArgumentException("\"$trimmed\" is a system preset's name"))
        }
        val preset = UserPreset(kind, trimmed, inherits, currentUser?.values.orEmpty() + values)
        runCatching { userPresets.save(preset) }.onFailure { return@withLock Result.failure(it) }
        publish(choices.userPresets.filterNot { it.kind == kind && it.name == trimmed } + preset)
        choose(kind, trimmed, slot)
        refreshLists()
        remember()
        Result.success(Unit)
    }

    /**
     * Adds [presets] to the user's, replacing any of the same kind and name.
     * One named as a system preset is added as "<name> - Imported". Returns
     * them as added.
     */
    suspend fun addUserPresets(presets: List<UserPreset>): Result<List<UserPreset>> = mutex.withLock {
        val choices = _choices.value
        val added = presets.map { preset ->
            val clashes = choices.userPreset(preset.kind, preset.name) == null && preset.name in systemNames(choices, preset.kind)
            if (clashes) preset.copy(name = "${preset.name} - Imported") else preset
        }
        runCatching { added.forEach { userPresets.save(it) } }.onFailure { return@withLock Result.failure(it) }
        publish(choices.userPresets.filterNot { old -> added.any { it.kind == old.kind && it.name == old.name } } + added)
        refreshLists()
        Result.success(added)
    }

    /** Renames the user's preset; where it is chosen, the new name is. */
    suspend fun renameUserPreset(kind: PresetKind, name: String, newName: String): Result<Unit> = mutex.withLock {
        val choices = _choices.value
        val preset = choices.userPreset(kind, name) ?: return@withLock Result.failure(IllegalArgumentException("No such preset"))
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == name) return@withLock Result.success(Unit)
        if (choices.userPreset(kind, trimmed) != null || trimmed in systemNames(choices, kind)) {
            return@withLock Result.failure(IllegalArgumentException("A preset is already called \"$trimmed\""))
        }
        runCatching {
            userPresets.save(preset.copy(name = trimmed))
            userPresets.delete(kind, name)
        }.onFailure { return@withLock Result.failure(it) }
        publish(choices.userPresets.map { if (it == preset) it.copy(name = trimmed) else it })
        replaceChosen(kind, name, trimmed)
        refreshLists()
        remember()
        Result.success(Unit)
    }

    /** Deletes the user's preset; where it is chosen, the system preset it inherits is. */
    suspend fun deleteUserPreset(kind: PresetKind, name: String): Result<Unit> = mutex.withLock {
        val choices = _choices.value
        val preset = choices.userPreset(kind, name) ?: return@withLock Result.success(Unit)
        runCatching { userPresets.delete(kind, name) }.onFailure { return@withLock Result.failure(it) }
        publish(choices.userPresets - preset)
        replaceChosen(kind, name, preset.inherits)
        refreshLists()
        remember()
        Result.success(Unit)
    }

    private suspend fun publish(users: List<UserPreset>) {
        val sorted = users.sortedBy { it.name.lowercase() }
        catalog.setUserPresets(sorted)
        _choices.update { it.copy(userPresets = sorted) }
    }

    private fun choose(kind: PresetKind, name: String, slot: Int) {
        _choices.update { c ->
            when (kind) {
                PresetKind.PRINTER -> c.copy(printer = name)
                PresetKind.PROCESS -> c.copy(process = name)
                PresetKind.FILAMENT -> c.copy(slots = c.slots.mapIndexed { i, s -> if (i == slot) s.copy(preset = name) else s })
            }
        }
    }

    private fun replaceChosen(kind: PresetKind, from: String, to: String) {
        _choices.update { c ->
            when (kind) {
                PresetKind.PRINTER -> if (c.printer == from) c.copy(printer = to) else c
                PresetKind.PROCESS -> if (c.process == from) c.copy(process = to) else c
                PresetKind.FILAMENT -> c.copy(slots = c.slots.map { s -> if (s.preset == from) s.copy(preset = to) else s })
            }
        }
    }

    /** The lists again (they name the user's presets), keeping what is chosen. */
    private suspend fun refreshLists() {
        val choices = _choices.value
        val vendor = choices.vendor ?: return
        val printers = catalog.printers(vendor).getOrElse { return fail(it) }
        _choices.update { it.copy(printers = printers) }
        val printer = choices.printer?.takeIf { it in printers } ?: return
        val compatible = catalog.compatiblePresets(vendor, printer).getOrElse { return fail(it) }
        val listed = listed(printer, compatible.filaments, choices.slots)
        _choices.update {
            it.copy(
                processes = compatible.processes,
                process = it.process?.takeIf { p -> p in compatible.processes } ?: compatible.defaultProcess,
                filaments = listed,
            )
        }
    }

    /**
     * The filaments [printer] lists of [all] it suits: those the user chose to
     * show (all when none were chosen), their own, and any loaded in [slots].
     */
    private suspend fun listed(printer: String, all: List<String>, slots: List<FilamentSlot>): List<String> {
        val shown = settings.shownFilaments(printer)
        if (shown.isEmpty()) return all
        val users = _choices.value.userNames(PresetKind.FILAMENT)
        val loaded = slots.map { it.preset }.toSet()
        return all.filter { it in shown || it in users || it in loaded }
    }

    /** Lists only [names] of the filaments [printer] suits (all of them when empty), as the desktop's filament selection. */
    suspend fun setShownFilaments(printer: String, names: Set<String>) = mutex.withLock {
        settings.saveShownFilaments(printer, names)
        if (_choices.value.printer == printer) refreshLists()
    }

    private fun systemNames(choices: PresetChoices, kind: PresetKind): List<String> = when (kind) {
        PresetKind.PRINTER -> choices.printers
        PresetKind.PROCESS -> choices.processes
        PresetKind.FILAMENT -> choices.filaments
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
