package com.orcamobile.core.model

/**
 * A printer the user keeps, as the desktop's Device tab and physical printer
 * list do: how to reach it ([host]), what to call it, and the printer preset
 * its prints are sliced with (when the user tied one to it).
 */
data class SavedPrinter(
    val id: String,
    val name: String,
    val host: PrintHostConfig,
    /** The printer preset its prints are sliced with; null for any. */
    val preset: String? = null,
)

/** A printer found on the local network, ready to be added. */
data class DiscoveredPrinter(
    val name: String,
    val model: String?,
    val host: PrintHostConfig,
    /** Something stable about it (a mainboard or serial id) to tell it from an already-saved one. */
    val uniqueId: String? = null,
)

/** What a printer is doing, as far as its connection says. */
enum class MachineState {
    /** Not yet heard from. */
    UNKNOWN,
    IDLE,
    /** Getting ready to print: heating, homing, levelling, checking the file. */
    PREPARING,
    PRINTING,
    PAUSING,
    PAUSED,
    STOPPING,
    /** The last print finished. */
    FINISHED,
    /** The last print was stopped. */
    CANCELLED,
    /** Something needs attention ([PrinterState.message] says what). */
    ERROR,
    /** Busy with something other than a print: a transfer, calibration, a self-test. */
    BUSY,
}

/** How the app's connection to a printer stands. */
sealed interface PrinterLink {
    data object Connecting : PrinterLink

    data object Online : PrinterLink

    /** Not reachable (yet); [reason] says why when known. */
    data class Offline(val reason: String?) : PrinterLink
}

/** A print job, as the printer reports it. */
data class PrintJob(
    val fileName: String,
    /** 0 to 1; null when the printer doesn't say. */
    val progress: Double?,
    val currentLayer: Int? = null,
    val totalLayers: Int? = null,
    val elapsedSeconds: Long? = null,
    val remainingSeconds: Long? = null,
)

enum class HeaterKind { NOZZLE, BED, CHAMBER }

data class Heater(val kind: HeaterKind, val current: Double, val target: Double, val maxTarget: Double? = null)

enum class FanKind {
    /** Part cooling. */
    PART,
    AUXILIARY,
    CHAMBER,
}

data class FanState(val kind: FanKind, val percent: Int)

/** A printer's state, as its connection last reported it. */
data class PrinterState(
    val link: PrinterLink = PrinterLink.Connecting,
    val machine: MachineState = MachineState.UNKNOWN,
    val job: PrintJob? = null,
    val heaters: List<Heater> = emptyList(),
    val fans: List<FanState> = emptyList(),
    /** Print speed, percent of the sliced speeds. */
    val speedPercent: Int? = null,
    val lightOn: Boolean? = null,
    /** Where the toolhead is (mm), when reported. */
    val position: Axes? = null,
    /** What the printer says needs attention, in plain words. */
    val message: String? = null,
    /** The printer's own name and firmware, when reported. */
    val model: String? = null,
    val firmware: String? = null,
    /** Filament slots (a Canvas, AMS or similar), when the printer has them. */
    val filamentSlots: List<FilamentSlotState> = emptyList(),
)

/** One filament slot of a printer's filament system. */
data class FilamentSlotState(
    /** 0-based: unit * slotsPerUnit + slot, as the printer counts. */
    val index: Int,
    val unit: Int,
    val loaded: Boolean,
    val type: String? = null,
    val colour: String? = null,
    val name: String? = null,
    val active: Boolean = false,
)

/** A file on the printer's storage. */
data class PrinterFile(
    val path: String,
    val name: String,
    val sizeBytes: Long? = null,
    val isFolder: Boolean = false,
)

/** A print the printer remembers. */
data class PrintRecord(
    val id: String,
    val fileName: String,
    val startedEpochSeconds: Long?,
    val endedEpochSeconds: Long?,
    val outcome: MachineState,
    val reason: String? = null,
)

/** Where to watch the printer's camera. */
sealed interface CameraFeed {
    /** A multipart MJPEG stream over HTTP. */
    data class Mjpeg(val url: String) : CameraFeed

    /** Single JPEG frames, fetched one after another. */
    data class Snapshots(val url: String, val intervalMillis: Long = 1_000) : CameraFeed
}
