package com.orcamobile.core.engine

import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.CompatiblePresets
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.OptionChoice
import com.orcamobile.core.model.OptionType
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

    @Test
    fun `decodes a bed shape`() {
        val payload = """{"outline":[[0,0],[235,0],[235,235],[0,235]],"maxHeight":250.0}"""
        assertEquals(
            BedShape(listOf(0.0 to 0.0, 235.0 to 0.0, 235.0 to 235.0, 0.0 to 235.0), 250.0),
            EngineJson.bedShape(payload).getOrThrow(),
        )
        assertIs<EngineException>(EngineJson.bedShape("""{"error":"Unknown printer preset: X"}""").exceptionOrNull())
    }

    @Test
    fun `decodes a loaded project with its handle as a string`() {
        val payload = """{"handle":"546054451200","objects":[{"name":"3DBenchy","instances":1,"triangles":225154,""" +
            """"size":[60.0,31.0,48.0]}]}"""
        val loaded = EngineJson.loadedProject(payload).getOrThrow()
        assertEquals(546054451200L, loaded.handle)
        assertEquals(listOf(ModelObjectInfo("3DBenchy", 1, 225154, listOf(60.0, 31.0, 48.0))), loaded.objects)
    }

    @Test
    fun `decodes a loaded G-code preview`() {
        val payload = """{"handle":"77","layerZs":[0.2,0.4],"printTimeSeconds":125.5,"moveCount":1200,""" +
            """"filamentColors":["#00AE42"]}"""
        val loaded = EngineJson.loadedPreview(payload).getOrThrow()
        assertEquals(77L, loaded.handle)
        assertEquals(listOf(0.2f, 0.4f), loaded.summary.layerZs)
        assertEquals(125.5, loaded.summary.printTimeSeconds)
        assertEquals(1200L, loaded.summary.moveCount)
        assertEquals(listOf("#00AE42"), loaded.summary.filamentColors)
        assertIs<EngineException>(EngineJson.loadedPreview("""{"error":"no moves"}""").exceptionOrNull())
    }

    @Test
    fun `decodes option definitions with their choices`() {
        val payload = """{"options":[{"key":"sparse_infill_pattern","type":"enum","label":"Sparse infill pattern",""" +
            """"fullLabel":"","category":"Strength","tooltip":"Line pattern","unit":"","mode":"simple","default":"crosshatch",""" +
            """"enumValues":["grid","gyroid"],"enumLabels":["Grid",""],"nullable":false},""" +
            """{"key":"layer_height","type":"float","label":"Layer height","unit":"mm","min":0.0,"enumValues":[],"enumLabels":[]}]}"""
        val (pattern, layer) = EngineJson.configOptions(payload).getOrThrow()
        assertEquals(OptionType.ENUM, pattern.type)
        assertEquals("Sparse infill pattern", pattern.fullLabel)
        assertEquals(listOf(OptionChoice("grid", "Grid"), OptionChoice("gyroid", "gyroid")), pattern.choices)
        assertEquals(OptionType.FLOAT, layer.type)
        assertEquals(0.0, layer.min)
        assertEquals(null, layer.max)
        assertEquals("mm", layer.unit)
    }

    @Test
    fun `decodes config values`() {
        assertEquals(
            mapOf("layer_height" to "0.2", "nozzle_temperature" to "220,220"),
            EngineJson.configValues("""{"values":{"layer_height":"0.2","nozzle_temperature":"220,220"}}""").getOrThrow(),
        )
    }

    @Test
    fun `decodes a plate update and encodes arrange options`() {
        val update = EngineJson.plateUpdate(
            """{"objects":[{"name":"cube","instances":4,"triangles":12,"size":[20,20,20]}],"placed":3,"notPlaced":1}""",
        ).getOrThrow()
        assertEquals(4, update.objects.single().instances)
        assertEquals(3, update.placed)
        assertEquals(1, update.notPlaced)
        assertEquals("No such instance", EngineJson.plateUpdate("""{"error":"No such instance"}""").exceptionOrNull()?.message)
        assertEquals(
            """{"spacingMm":6.0,"allowRotation":true,"alignToYAxis":false}""",
            EngineJson.encode(com.orcamobile.core.model.ArrangeOptions(spacingMm = 6.0, allowRotation = true)),
        )
    }

    @Test
    fun `decodes a transform and encodes transform edits`() {
        val update = EngineJson.transformUpdate(
            """{"objects":[],"transform":{"position":[110,100,10],"rotation":[0,0,90],"scale":[1,1,2],""" +
                """"size":[20,20,40],"mirrored":[true,false,false]}}""",
        ).getOrThrow()
        val t = update.transform
        assertEquals(com.orcamobile.core.model.Axes(110.0, 100.0, 10.0), t.position)
        assertEquals(90.0, t.rotationDegrees.z)
        assertEquals(setOf(com.orcamobile.core.model.Axis.X), t.mirrored)

        assertEquals("", EngineJson.encode(null as com.orcamobile.core.model.TransformEdit?))
        assertEquals("""{"position":[1.0,2.0]}""", EngineJson.encode(com.orcamobile.core.model.TransformEdit.Position(1.0, 2.0)))
        assertEquals("""{"mirror":2}""", EngineJson.encode(com.orcamobile.core.model.TransformEdit.Mirror(com.orcamobile.core.model.Axis.Z)))
        assertEquals("""{"reset":true}""", EngineJson.encode(com.orcamobile.core.model.TransformEdit.Reset))
    }
}
