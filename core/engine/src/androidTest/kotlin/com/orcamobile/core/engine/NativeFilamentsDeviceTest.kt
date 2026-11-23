package com.orcamobile.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.engine.jni.NativeProgressListener
import com.orcamobile.core.engine.resources.EngineResources
import com.orcamobile.core.model.ObjectEdit
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrimitiveShape
import com.orcamobile.core.model.SettingTarget
import com.orcamobile.core.model.SliceResult
import com.orcamobile.core.model.VolumeKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Several filaments on a device, on the Elegoo Centauri Carbon's own presets
 * (a single-nozzle multi-colour printer): colours and purge volumes, an object
 * on the second filament, and taking a filament away.
 */
@RunWith(AndroidJUnit4::class)
class NativeFilamentsDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "filaments-test").apply { mkdirs() }
    private var handle = 0L

    private val vendor = "Elegoo"
    private val printer = "Elegoo Centauri Carbon 0.4 nozzle"

    @Before
    fun setUp() {
        assertNull("The engine library failed to load", NativeEngine.loadError)
        EngineEnvironment(context, EngineResources(context)).ensureInitialized()
    }

    @After
    fun tearDown() {
        if (handle != 0L) NativeEngine.nativeProjectRelease(handle)
    }

    private fun twoFilaments(): PresetSelection {
        val compatible = EngineJson.compatiblePresets(NativeEngine.nativeCompatiblePresets(vendor, printer)).getOrThrow()
        val process = requireNotNull(compatible.defaultProcess ?: compatible.processes.firstOrNull())
        val filament = requireNotNull(compatible.defaultFilament ?: compatible.filaments.firstOrNull())
        return PresetSelection(vendor, printer, process, listOf(filament, filament), listOf("#FF0000", "#0000FF"))
    }

    private fun values(selection: PresetSelection, vararg keys: String): Map<String, String> =
        EngineJson.configValues(NativeEngine.nativeConfigValues(EngineJson.encode(selection), arrayOf(*keys))).getOrThrow()

    @Test
    fun coloursAndPurgeVolumesFollowTheFilaments() {
        val selection = twoFilaments()
        val config = values(selection, "filament_colour", "flush_volumes_matrix", "filament_map")
        assertEquals("#FF0000;#0000FF", config["filament_colour"])
        assertEquals("1,1", config["filament_map"])
        val matrix = config.getValue("flush_volumes_matrix").split(',').map { it.toDouble() }
        assertEquals(4, matrix.size)
        assertEquals(0.0, matrix[0], 0.0)
        assertEquals(0.0, matrix[3], 0.0)
        assertTrue("purge red to blue ${matrix[1]}", matrix[1] > 0)
        assertTrue("purge blue to red ${matrix[2]}", matrix[2] > 0)

        // Without its own colour a slot keeps its preset's.
        val plain = values(selection.copy(filamentColours = emptyList()), "filament_colour")
        assertEquals(2, plain.getValue("filament_colour").split(';').size)
    }

    @Test
    fun anObjectOnTheSecondFilamentChangesTool() {
        loadTwoObjects()
        edit(1, ObjectEdit.SetSetting(SettingTarget.OBJECT, "extruder", "2"))

        val gcode = File(work, "two.gcode")
        val payload = NativeEngine.nativeSliceProject(
            handle, gcode.path, EngineJson.encode(twoFilaments()), emptyArray(), emptyArray(), Quiet, 0L,
        )
        val result = EngineJson.sliceResult(payload, gcode.path)
        assertTrue("$result", result is SliceResult.Succeeded)
        val text = gcode.readText()
        assertTrue("the second filament is used", Regex("^T1\\b", RegexOption.MULTILINE).containsMatchIn(text))
    }

    @Test
    fun takingAFilamentAwayMovesWhatUsedIt() {
        loadTwoObjects()
        edit(1, ObjectEdit.SetSetting(SettingTarget.OBJECT, "extruder", "2"))
        NativeEngine.nativeProjectFilaments(handle, 1, 1)
        assertEquals("1", details(1).settings["extruder"])

        // A printer with fewer filaments: nothing points past them.
        edit(0, ObjectEdit.SetSetting(SettingTarget.OBJECT, "extruder", "3"))
        NativeEngine.nativeProjectFilaments(handle, -1, 2)
        assertEquals(null, details(0).settings["extruder"])
    }

    /** A cube and a second part beside it, split into two objects. */
    private fun loadTwoObjects() {
        handle = EngineJson.loadedProject(NativeEngine.nativeProjectLoad(box("cube", 20f).path)).getOrThrow().handle
        NativeEngine.nativeProjectCenterOn(handle, 128.0, 128.0)
        edit(0, ObjectEdit.AddPrimitive(VolumeKind.PART, PrimitiveShape.entries.first(), 15.0))
        val split = EngineJson.plateUpdate(NativeEngine.nativePlateSplit(handle, 0)).getOrThrow()
        assertEquals(2, split.objects.size)
        // Apart on the bed, so both print.
        val keys = arrayOf("printable_area")
        val bed = arrayOf("0x0,256x0,256x256,0x256")
        EngineJson.plateUpdate(
            NativeEngine.nativePlateArrange(handle, "", keys, bed, EngineJson.encode(com.orcamobile.core.model.ArrangeOptions())),
        ).getOrThrow()
    }

    private fun edit(objectIndex: Int, edit: ObjectEdit) =
        EngineJson.objectUpdate(NativeEngine.nativeObjectEdit(handle, objectIndex, EngineJson.encodeObjectEdit(edit))).getOrThrow()

    private fun details(objectIndex: Int) =
        EngineJson.objectUpdate(NativeEngine.nativeObjectDetails(handle, objectIndex)).getOrThrow().details

    private object Quiet : NativeProgressListener {
        override fun onProgress(percent: Int, message: String) = Unit
    }

    /** An ASCII STL cube of [size] mm, its corner at the origin. */
    private fun box(name: String, size: Float): File {
        val v = listOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(size, 0f, 0f), floatArrayOf(size, size, 0f), floatArrayOf(0f, size, 0f),
            floatArrayOf(0f, 0f, size), floatArrayOf(size, 0f, size), floatArrayOf(size, size, size), floatArrayOf(0f, size, size),
        )
        val faces = listOf(
            intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
            intArrayOf(0, 1, 5), intArrayOf(0, 5, 4), intArrayOf(1, 2, 6), intArrayOf(1, 6, 5),
            intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
        )
        val text = buildString {
            appendLine("solid $name")
            for (f in faces) {
                appendLine("facet normal 0 0 0")
                appendLine("outer loop")
                for (i in f) appendLine("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}")
                appendLine("endloop")
                appendLine("endfacet")
            }
            appendLine("endsolid $name")
        }
        return File(work, "$name.stl").apply { writeText(text) }
    }
}
