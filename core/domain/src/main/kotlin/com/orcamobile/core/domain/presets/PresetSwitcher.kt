package com.orcamobile.core.domain.presets

import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.element
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** A preset to change to. */
sealed interface PresetChange {
    val kind: PresetKind

    data class Printer(val printer: String) : PresetChange {
        override val kind get() = PresetKind.PRINTER
    }

    data class Process(val process: String) : PresetChange {
        override val kind get() = PresetKind.PROCESS
    }

    /** Filament [slot] + 1 loads [filament]. */
    data class Filament(val slot: Int, val filament: String) : PresetChange {
        override val kind get() = PresetKind.FILAMENT
    }
}

/** What happens to the changed settings of the preset being left, as the desktop's unsaved-changes dialog asks. */
sealed interface UnsavedChoice {
    /** The changes go over the new preset ("Transfer"). */
    data object Keep : UnsavedChoice

    data object Discard : UnsavedChoice

    /** The changes are saved as the user's preset [name] first, then left behind with it. */
    data class Save(val name: String) : UnsavedChoice
}

/** A preset change waiting on what to do with [changes] changed settings of the preset [from]. */
data class UnsavedPrompt(
    val change: PresetChange,
    val changes: Int,
    val from: String,
    val to: String,
    /** Why saving failed (a name already taken), shown with the name. */
    val error: String? = null,
)

/**
 * Changes presets without losing settings by surprise: the changes to the
 * preset being left are kept, discarded or saved as the user chooses, and
 * the rest (another kind's) carry over to the new presets.
 */
@Singleton
class PresetSwitcher @Inject constructor(
    private val picker: PresetPicker,
    private val printSettings: PrintSettingsRepository,
    private val catalog: ConfigOptionCatalog,
) {
    /** How many changed settings [change] leaves behind (0: nothing to ask). */
    suspend fun unsaved(change: PresetChange): Int {
        if (isCurrent(change)) return 0
        val changes = currentChanges()
        if (changes.isEmpty()) return 0
        return affected(change, changes).size
    }

    /**
     * Makes [change] at once when it leaves no changes behind (null), or returns
     * what to ask the user, whose answer goes to [switch].
     */
    suspend fun request(change: PresetChange): UnsavedPrompt? {
        val count = unsaved(change)
        if (count == 0) {
            switch(change).getOrThrow()
            return null
        }
        val choices = picker.choices.value
        val slot = (change as? PresetChange.Filament)?.slot ?: 0
        val to = when (change) {
            is PresetChange.Printer -> change.printer
            is PresetChange.Process -> change.process
            is PresetChange.Filament -> change.filament
        }
        return UnsavedPrompt(change, count, choices.chosen(change.kind, slot).orEmpty(), to)
    }

    /** Makes [change], doing [choice] with the changes it leaves behind. */
    suspend fun switch(change: PresetChange, choice: UnsavedChoice = UnsavedChoice.Keep): Result<Unit> = runCatching {
        val changes = currentChanges()
        val affected = affected(change, changes)
        if (choice is UnsavedChoice.Save) {
            val slot = (change as? PresetChange.Filament)?.slot ?: 0
            picker.saveUserPreset(change.kind, choice.name, presetValues(change.kind, affected, slot), slot).getOrThrow()
        }
        when (change) {
            is PresetChange.Printer -> picker.selectPrinter(change.printer)
            is PresetChange.Process -> picker.selectProcess(change.process)
            is PresetChange.Filament -> picker.selectFilament(change.slot, change.filament)
        }
        carryOver(if (choice == UnsavedChoice.Keep) changes else changes - affected.keys)
    }

    /**
     * [kept] become the changes over the presets now chosen, less those the
     * presets already give. Without a complete selection nothing changes.
     */
    suspend fun carryOver(kept: Map<String, String>) {
        val selection = picker.choices.value.selection ?: return
        val now = if (kept.isEmpty()) emptyMap() else catalog.values(selection, kept.keys.toList()).getOrDefault(emptyMap())
        printSettings.replace(selection, kept.filter { (key, value) -> now[key] != value })
    }

    /**
     * What a preset of [kind] holds of [changes]: its own settings, and for a
     * filament, filament [slot] + 1's value of each.
     */
    suspend fun presetValues(kind: PresetKind, changes: Map<String, String>, slot: Int = 0): Map<String, String> {
        val mine = changes.filterKeys { it in kindKeys(kind) }
        if (kind != PresetKind.FILAMENT || mine.isEmpty()) return mine
        val options = catalog.describe(mine.keys.toList()).getOrThrow().associateBy { it.key }
        return mine.mapValues { (key, value) -> options[key]?.element(value, slot) ?: value }
    }

    /** The changes stored, whichever presets they were made over. */
    private suspend fun currentChanges(): Map<String, String> = printSettings.edits.first().values

    /** The changes to the preset [change] leaves: a new printer leaves all of them (the process and filaments go with it). */
    private suspend fun affected(change: PresetChange, changes: Map<String, String>): Map<String, String> =
        if (change is PresetChange.Printer) changes else changes.filterKeys { it in kindKeys(change.kind) }

    private suspend fun kindKeys(kind: PresetKind): Set<String> = catalog.presetOptionKeys().getOrThrow().of(kind).toSet()

    private fun isCurrent(change: PresetChange): Boolean {
        val choices = picker.choices.value
        return when (change) {
            is PresetChange.Printer -> choices.printer == change.printer
            is PresetChange.Process -> choices.process == change.process
            is PresetChange.Filament -> choices.chosen(PresetKind.FILAMENT, change.slot) == change.filament
        }
    }
}
