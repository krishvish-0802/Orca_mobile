package com.orcamobile.core.engine

import com.orcamobile.core.model.CompatiblePresets
import com.orcamobile.core.model.EngineInfo
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.SliceResult
import com.orcamobile.core.model.SliceStatistics
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Payloads here match what native/bridge/src/JsonCodec.cpp produces. */
class EngineJsonTest {

    @Test
    fun `decodes engine info`() {
        val payload = """{"appName":"OrcaSlicer","orcaVersion":"2.6.0-dev","slic3rVersion":"02.08.01.55","upstreamCommit":"0c62aa085ea9"}"""
        assertEquals(
            EngineInfo("OrcaSlicer", "2.6.0-dev", "02.08.01.55", "0c62aa085ea9"),
            EngineJson.engineInfo(payload),
        )
    }

    @Test
    fun `decodes a successful slice`() {
        val payload = """{"status":"succeeded","statistics":{"printTimeSeconds":1234.5,"filamentUsedMm":3210.0,""" +
            """"filamentUsedG":9.6,"layerCount":100,"gcodeBytes":456789}}"""
        assertEquals(
            SliceResult.Succeeded("/out/a.gcode", SliceStatistics(1234.5, 3210.0, 9.6, 100, 456789)),
            EngineJson.sliceResult(payload, "/out/a.gcode"),
        )
    }

    @Test
    fun `decodes cancellation and failure`() {
        assertEquals(SliceResult.Canceled, EngineJson.sliceResult("""{"status":"canceled"}""", "/x"))
        assertEquals(
            SliceResult.Failed("Unknown config option: foo"),
            EngineJson.sliceResult("""{"status":"failed","error":"Unknown config option: foo"}""", "/x"),
        )
    }

    @Test
    fun `treats success without statistics as a failure`() {
        val result = EngineJson.sliceResult("""{"status":"succeeded"}""", "/x")
        assertEquals(SliceResult.Failed("Engine reported success without statistics"), result)
    }

    @Test
    fun `decodes string lists and engine errors`() {
        assertEquals(listOf("Creality", "Prusa"), EngineJson.stringList("""["Creality","Prusa"]""").getOrThrow())
        val failure = assertIs<EngineException>(EngineJson.stringList("""{"error":"Unknown vendor: Foo"}""").exceptionOrNull())
        assertEquals("Unknown vendor: Foo", failure.message)
    }

    @Test
    fun `decodes compatible presets, treating empty defaults as none`() {
        val payload = """{"processes":["0.20mm Standard"],"filaments":["Generic PLA"],"defaultProcess":"0.20mm Standard","defaultFilament":""}"""
        assertEquals(
            CompatiblePresets(listOf("0.20mm Standard"), listOf("Generic PLA"), "0.20mm Standard", null),
            EngineJson.compatiblePresets(payload).getOrThrow(),
        )
    }

    @Test
    fun `encodes a preset selection for the bridge`() {
        val selection = PresetSelection("Creality", "K1 (0.4 nozzle)", "0.20mm Standard", listOf("Generic PLA"))
        assertEquals(
            """{"vendor":"Creality","printer":"K1 (0.4 nozzle)","process":"0.20mm Standard","filaments":["Generic PLA"]}""",
            EngineJson.encode(selection),
        )
        assertEquals("", EngineJson.encode(null))
    }
}
