package com.orcamobile.feature.devices

import androidx.lifecycle.SavedStateHandle
import com.orcamobile.core.domain.printer.CameraFrames
import com.orcamobile.core.domain.printer.CameraSource
import com.orcamobile.core.domain.printer.FanControl
import com.orcamobile.core.domain.printer.GcodeConsole
import com.orcamobile.core.domain.printer.JobControl
import com.orcamobile.core.domain.printer.LightControl
import com.orcamobile.core.domain.printer.MotionControl
import com.orcamobile.core.domain.printer.PrintHistory
import com.orcamobile.core.domain.printer.PrintHostClient
import com.orcamobile.core.domain.printer.PrintHostClientFactory
import com.orcamobile.core.domain.printer.PrinterConnection
import com.orcamobile.core.domain.printer.PrinterConnectionFactory
import com.orcamobile.core.domain.printer.PrinterDiscovery
import com.orcamobile.core.domain.printer.PrinterFiles
import com.orcamobile.core.domain.printer.PrinterRepository
import com.orcamobile.core.domain.printer.PrinterSessions
import com.orcamobile.core.domain.printer.TemperatureControl
import com.orcamobile.core.domain.usecase.TestPrintHostUseCase
import com.orcamobile.core.model.CameraFeed
import com.orcamobile.core.model.DiscoveredPrinter
import com.orcamobile.core.model.HeaterKind
import com.orcamobile.core.model.MachineState
import com.orcamobile.core.model.PrintHostConfig
import com.orcamobile.core.model.PrintHostInfo
import com.orcamobile.core.model.PrintHostType
import com.orcamobile.core.model.PrintJob
import com.orcamobile.core.model.PrintStartOptions
import com.orcamobile.core.model.PrinterLink
import com.orcamobile.core.model.PrinterState
import com.orcamobile.core.model.SavedPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelsTest {

    private val centauri = SavedPrinter("p1", "Centauri", PrintHostConfig(PrintHostType.ELEGOO_LINK, "192.168.1.40"))
    private val klipper = SavedPrinter("p2", "Voron", PrintHostConfig(PrintHostType.MOONRAKER, "192.168.1.50"))

    private class Repository(initial: List<SavedPrinter>) : PrinterRepository {
        val list = MutableStateFlow(initial)
        val chosen = MutableStateFlow(initial.firstOrNull()?.id)
        override val printers: Flow<List<SavedPrinter>> = list
        override val selected: Flow<String?> = chosen
        override suspend fun save(printer: SavedPrinter) {
            list.value = list.value.filterNot { it.id == printer.id } + printer
        }
        override suspend fun remove(id: String) {
            list.value = list.value.filterNot { it.id == id }
        }
        override suspend fun select(id: String) {
            chosen.value = id
        }
    }

    /** A printer that does what it's told, and remembers it. */
    private class FakeConnection : PrinterConnection {
        val calls = mutableListOf<String>()
        override val state = MutableStateFlow(
            PrinterState(link = PrinterLink.Online, machine = MachineState.PRINTING, job = PrintJob("cube.gcode", 0.4)),
        )
        override val jobs = object : JobControl {
            override suspend fun pause() = Result.success(Unit).also { calls += "pause" }
            override suspend fun resume() = Result.success(Unit).also { calls += "resume" }
            override suspend fun stop() = Result.failure<Unit>(IllegalStateException("The printer is busy")).also { calls += "stop" }
        }
        override val temperatures = object : TemperatureControl {
            override val heaters = setOf(HeaterKind.NOZZLE, HeaterKind.BED)
            override suspend fun setTarget(heater: HeaterKind, celsius: Int) = Result.success(Unit).also { calls += "$heater=$celsius" }
        }
        override val fans: FanControl? = null
        override val motion: MotionControl? = null
        override val light: LightControl? = null
        override val camera = object : CameraSource {
            override suspend fun open() = Result.success<CameraFeed>(CameraFeed.Mjpeg("http://camera")).also { calls += "camera on" }
            override suspend fun release() {
                calls += "camera off"
            }
        }
        override val files: PrinterFiles? = null
        override val history: PrintHistory? = null
        override val console: GcodeConsole? = null
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private val connection = FakeConnection()

    /** Elegoo printers have a live connection; the others only take uploads here. */
    private val factory = object : PrinterConnectionFactory {
        override fun connect(host: PrintHostConfig) = if (host.type == PrintHostType.ELEGOO_LINK) connection else null
    }

    private val frames = object : CameraFrames {
        override fun frames(feed: CameraFeed) = emptyFlow<ByteArray>()
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the list shows each printer's live status, and the one prints go to`() = runTest {
        val repository = Repository(listOf(centauri, klipper))
        val vm = PrintersViewModel(repository, PrinterSessions(factory))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        val cards = vm.state.first { !it.loading }.printers
        assertEquals(listOf("p1", "p2"), cards.map { it.printer.id })
        assertEquals(MachineState.PRINTING, cards[0].state?.machine)
        assertNull(cards[1].state)
        assertEquals(listOf(true, false), cards.map { it.selected })

        vm.onIntent(PrintersIntent.Select("p2"))
        assertEquals(listOf(false, true), vm.state.first { it.printers.getOrNull(1)?.selected == true }.printers.map { it.selected })

        // A printer no longer kept has its connection closed.
        repository.remove("p1")
        assertEquals(listOf("p2"), vm.state.first { it.printers.size == 1 }.printers.map { it.printer.id })
        assertTrue(connection.closed)
    }

    @Test
    fun `a printer's controls reach it, and what it refuses is shown`() = runTest {
        val vm = PrinterViewModel(SavedStateHandle(mapOf(DEVICES_PRINTER_ARG to "p1")), Repository(listOf(centauri)), PrinterSessions(factory), frames)
        val state = vm.awaitPrinter()
        assertTrue(state.abilities.jobs)
        assertEquals(setOf(HeaterKind.NOZZLE, HeaterKind.BED), state.abilities.heaters)
        assertEquals("cube.gcode", vm.state.value.status?.job?.fileName)

        vm.onIntent(PrinterIntent.Pause)
        vm.onIntent(PrinterIntent.SetTemperature(HeaterKind.BED, 70))
        vm.onIntent(PrinterIntent.Stop)
        assertEquals(listOf("pause", "BED=70", "stop"), connection.calls)
        assertEquals("The printer is busy", vm.state.value.problem)

        vm.onIntent(PrinterIntent.ToggleCamera)
        assertTrue(vm.state.value.camera is CameraUi.Showing)
        vm.onIntent(PrinterIntent.ToggleCamera)
        assertEquals(CameraUi.Off, vm.state.value.camera)
        assertEquals(listOf("camera on", "camera off"), connection.calls.takeLast(2))
    }

    @Test
    fun `a printer found on the network is added, and one already kept isn't offered`() = runTest {
        val repository = Repository(listOf(centauri))
        val discovery = object : PrinterDiscovery {
            override fun discover(timeoutMillis: Long) = flowOf(
                DiscoveredPrinter("Centauri", "ELEGOO Centauri Carbon", centauri.host, "a"),
                DiscoveredPrinter("Spare", "ELEGOO Centauri Carbon", PrintHostConfig(PrintHostType.ELEGOO_LINK, "192.168.1.41"), "b"),
            )
        }
        val hosts = object : PrintHostClientFactory {
            override fun create(config: PrintHostConfig) = object : PrintHostClient {
                override suspend fun testConnection() = Result.success(PrintHostInfo("Elegoo printer (ElegooLink)"))
                override suspend fun upload(
                    localPath: String,
                    remoteName: String,
                    startPrint: Boolean,
                    options: PrintStartOptions,
                    onProgress: (sent: Long, total: Long) -> Unit,
                ) = Result.success(Unit)
            }
        }
        val vm = EditPrinterViewModel(SavedStateHandle(), repository, discovery, TestPrintHostUseCase(hosts))
        val found = vm.state.first { !it.scanning }.found
        assertEquals(listOf("Spare"), found.map { it.name })

        vm.onIntent(EditPrinterIntent.Use(found.single()))
        vm.onIntent(EditPrinterIntent.Test)
        assertEquals(ConnectionTest.Passed("Elegoo printer (ElegooLink)"), vm.state.value.test)
        vm.onIntent(EditPrinterIntent.Save)
        assertEquals(EditPrinterEvent.Done, vm.events.first())
        assertEquals(listOf("Centauri", "Spare"), repository.list.value.map { it.name })
        assertEquals("192.168.1.41", repository.list.value.last().host.baseUrl)
    }
}
