package com.orcamobile.core.domain.printer

import com.orcamobile.core.model.Axis
import com.orcamobile.core.model.CameraFeed
import com.orcamobile.core.model.DiscoveredPrinter
import com.orcamobile.core.model.FanKind
import com.orcamobile.core.model.HeaterKind
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintRecord
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.PrinterFile
import com.orcamobile.core.model.PrinterState
import com.orcamobile.core.model.SavedPrinter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A live connection to one printer: its state as it changes, and what it can
 * be asked to do. Each kind of control is its own small interface, null where
 * the printer's protocol doesn't offer it, so screens show only what works.
 */
interface PrinterConnection : AutoCloseable {
    val state: StateFlow<PrinterState>

    val jobs: JobControl?
    val temperatures: TemperatureControl?
    val fans: FanControl?
    val motion: MotionControl?
    val light: LightControl?
    val camera: CameraSource?
    val files: PrinterFiles?
    val history: PrintHistory?
    val console: GcodeConsole?

    /** Stops listening to the printer. */
    override fun close()
}

/** Pausing, resuming and stopping the print in progress. */
interface JobControl {
    suspend fun pause(): Result<Unit>

    suspend fun resume(): Result<Unit>

    suspend fun stop(): Result<Unit>
}

interface TemperatureControl {
    /** The heaters that can be set. */
    val heaters: Set<HeaterKind>

    suspend fun setTarget(heater: HeaterKind, celsius: Int): Result<Unit>
}

interface FanControl {
    val fans: Set<FanKind>

    suspend fun setFan(fan: FanKind, percent: Int): Result<Unit>

    /** Print speed, percent of the sliced speeds; null where it can't be set. */
    val speedRange: IntRange?

    suspend fun setSpeed(percent: Int): Result<Unit>
}

interface MotionControl {
    /** Homes [axes] (all of them when empty). */
    suspend fun home(axes: Set<Axis> = emptySet()): Result<Unit>

    /** Moves [axis] by [millimetres], relative to where it is; null where the printer can't jog. */
    val canJog: Boolean

    suspend fun jog(axis: Axis, millimetres: Double): Result<Unit>
}

interface LightControl {
    suspend fun setLight(on: Boolean): Result<Unit>
}

interface CameraSource {
    /** Where to watch, turning the printer's camera on when it needs that. */
    suspend fun open(): Result<CameraFeed>

    /** Lets the printer turn its camera off again when nobody watches. */
    suspend fun release()
}

interface PrinterFiles {
    suspend fun list(folder: String? = null): Result<List<PrinterFile>>

    /** Starts printing a file already on the printer. */
    suspend fun print(file: PrinterFile, options: PrintStartOptions = PrintStartOptions()): Result<Unit>

    suspend fun delete(files: List<PrinterFile>): Result<Unit>
}

interface PrintHistory {
    suspend fun recent(): Result<List<PrintRecord>>
}

interface GcodeConsole {
    suspend fun send(gcode: String): Result<Unit>

    /** What the printer answers (Klipper's console, OctoPrint's terminal). */
    val output: Flow<String>
}

/** Opens a [PrinterConnection] for a printer's host settings; null where the protocol only uploads. */
interface PrinterConnectionFactory {
    fun connect(host: PrintHostConfig): PrinterConnection?
}

/** The printers the user keeps. */
interface PrinterRepository {
    val printers: Flow<List<SavedPrinter>>

    /** The printer prints go to by default (the one chosen last). */
    val selected: Flow<String?>

    suspend fun save(printer: SavedPrinter)

    suspend fun remove(id: String)

    suspend fun select(id: String)
}

/** A printer camera's pictures, as JPEG frames, while collected. */
interface CameraFrames {
    fun frames(feed: CameraFeed): Flow<ByteArray>
}

/** Finds printers on the local network. */
interface PrinterDiscovery {
    /** Printers that answer, as they answer, until the flow is cancelled or [timeoutMillis] passes. */
    fun discover(timeoutMillis: Long = 5_000): Flow<DiscoveredPrinter>
}
