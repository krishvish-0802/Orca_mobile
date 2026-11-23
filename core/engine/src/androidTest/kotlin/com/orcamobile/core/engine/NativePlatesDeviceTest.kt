package com.orcamobile.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.engine.jni.NativeProgressListener
import com.orcamobile.core.engine.resources.EngineResources
import com.orcamobile.core.model.ArrangeOptions
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.PlateAction
import com.orcamobile.core.model.PlateSettingKeys
import com.orcamobile.core.model.Plates
import com.orcamobile.core.model.SliceResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Several plates on a device, through the JNI calls the app makes: which
 * instances are on which plate, slicing one plate, plate settings, Arrange
 * all plates and removing a plate.
 */
@RunWith(AndroidJUnit4::class)
class NativePlatesDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "plates-test").apply { mkdirs() }

    private val keys = arrayOf("printable_area", "printable_height", "layer_height", "initial_layer_print_height")
    private val values = arrayOf("0x0,220x0,220x220,0x220", "250", "0.2", "0.2")
    private val options = EngineJson.encode(ArrangeOptions())
    private var handle = 0L

    @Before
    fun setUp() {
        assertNull("The engine library failed to load", NativeEngine.loadError)
        EngineEnvironment(context, EngineResources(context)).ensureInitialized()
    }

    @After
    fun tearDown() {
        if (handle != 0L) NativeEngine.nativeProjectRelease(handle)
    }

    private fun load(file: File) {
        handle = EngineJson.loadedProject(NativeEngine.nativeProjectLoad(file.path)).getOrThrow().handle
        NativeEngine.nativeProjectCenterOn(handle, 110.0, 110.0)
    }

    private fun plates(action: PlateAction = PlateAction.Get): Plates =
        EngineJson.plates(NativeEngine.nativePlates(handle, EngineJson.encode(action))).getOrThrow()

    private fun slice(name: String): SliceResult {
        val gcode = File(work, "$name.gcode")
        return EngineJson.sliceResult(NativeEngine.nativeSliceProject(handle, gcode.path, "", keys, values, Quiet, 0L), gcode.path)
    }

    /** A cube with a copy, the copy on a second plate. */
    private fun cubeOnTwoPlates() {
        load(box("cube", 20f, 20f, 20f))
        EngineJson.plateUpdate(NativeEngine.nativePlateAddCopies(handle, 0, 0, 1, "", keys, values, options)).getOrThrow()
        plates(PlateAction.Add)
        plates(PlateAction.Move(InstanceRef(0, 1), 1))
    }

    @Test
    fun instancesKeepTheirPlates() {
        load(box("cube", 20f, 20f, 20f))
        val start = plates()
        assertEquals(1, start.plates.size)
        assertEquals(listOf(0), start.objects.single().plates)

        val added = plates(PlateAction.Add)
        assertEquals(2, added.plates.size)
        assertEquals("adding a plate does not show it", 0, added.current)

        // A copy is made on the plate shown.
        EngineJson.plateUpdate(NativeEngine.nativePlateAddCopies(handle, 0, 0, 1, "", keys, values, options)).getOrThrow()
        assertEquals(listOf(0, 0), plates().objects.single().plates)

        val moved = plates(PlateAction.Move(InstanceRef(0, 1), 1))
        assertEquals(listOf(0, 1), moved.objects.single().plates)
        assertEquals(listOf(1, 1), moved.plates.map { it.instances })

        val renamed = plates(PlateAction.Rename(1, "Spares"))
        assertEquals("Spares", renamed.plates[1].name)
        assertEquals(1, plates(PlateAction.Select(1)).current)
    }

    @Test
    fun slicingTakesOnlyTheCurrentPlate() {
        cubeOnTwoPlates()
        val one = slice("one") as SliceResult.Succeeded

        plates(PlateAction.Move(InstanceRef(0, 1), 0))
        val two = slice("two") as SliceResult.Succeeded
        val ratio = two.statistics.filamentUsedMm / one.statistics.filamentUsedMm
        assertTrue("two cubes use $ratio times the filament of one", ratio in 1.7..2.3)
    }

    @Test
    fun anEmptyPlateDoesNotSlice() {
        load(box("cube", 20f, 20f, 20f))
        plates(PlateAction.Add)
        plates(PlateAction.Select(1))
        val result = slice("empty")
        assertTrue("$result", result is SliceResult.Failed && result.message.contains("empty"))
    }

    @Test
    fun plateSettingsAreCheckedKeptAndSliced() {
        cubeOnTwoPlates()
        val set = plates(PlateAction.SetSetting(1, PlateSettingKeys.PRINT_SEQUENCE, PlateSettingKeys.BY_OBJECT))
        assertEquals(PlateSettingKeys.BY_OBJECT, set.plates[1].settings[PlateSettingKeys.PRINT_SEQUENCE])
        assertTrue(set.plates[0].settings.isEmpty())

        val bad = NativeEngine.nativePlates(handle, EngineJson.encode(PlateAction.SetSetting(1, "layer_height", "0.1")))
        assertTrue(bad, bad.contains("error"))
        val badValue = NativeEngine.nativePlates(handle, EngineJson.encode(PlateAction.SetSetting(1, PlateSettingKeys.BED_TYPE, "Glass")))
        assertTrue(badValue, badValue.contains("error"))

        plates(PlateAction.SetSetting(1, PlateSettingKeys.SPIRAL_VASE, "1"))
        plates(PlateAction.Select(1))
        val gcode = File(work, "vase.gcode")
        val result = EngineJson.sliceResult(NativeEngine.nativeSliceProject(handle, gcode.path, "", keys, values, Quiet, 0L), gcode.path)
        assertTrue("$result", result is SliceResult.Succeeded)
        assertTrue("the plate's spiral vase reached the G-code", gcode.readText().contains("spiral_mode = 1"))

        val reset = plates(PlateAction.ResetSetting(1, PlateSettingKeys.SPIRAL_VASE))
        assertEquals(null, reset.plates[1].settings[PlateSettingKeys.SPIRAL_VASE])
    }

    @Test
    fun arrangeAllPlatesSpillsOntoNewPlates() {
        load(box("slab", 150f, 150f, 5f))
        EngineJson.plateUpdate(NativeEngine.nativePlateAddCopies(handle, 0, 0, 1, "", keys, values, options)).getOrThrow()
        val arranged = EngineJson.plateUpdate(
            NativeEngine.nativePlateArrange(handle, "", keys, values, EngineJson.encode(ArrangeOptions(), allPlates = true)),
        ).getOrThrow()
        assertEquals(2, arranged.placed)
        assertEquals(0, arranged.notPlaced)
        val after = plates()
        assertEquals(2, after.plates.size)
        assertEquals(listOf(1, 1), after.plates.map { it.instances })

        // Each plate slices on its own: both slabs lie on the bed.
        assertTrue(slice("first") is SliceResult.Succeeded)
        plates(PlateAction.Select(1))
        assertTrue(slice("second") is SliceResult.Succeeded)
    }

    @Test
    fun removingAPlateTakesItsObjects() {
        cubeOnTwoPlates()
        plates(PlateAction.Select(1))
        val removed = plates(PlateAction.Remove(1))
        assertEquals(1, removed.plates.size)
        assertEquals(0, removed.current)
        assertEquals(1, removed.objects.single().instances)

        val last = NativeEngine.nativePlates(handle, EngineJson.encode(PlateAction.Remove(0)))
        assertTrue("the last plate stays", last.contains("error"))
    }

    private object Quiet : NativeProgressListener {
        override fun onProgress(percent: Int, message: String) = Unit
    }

    /** An ASCII STL box of [x] by [y] by [z] mm, its corner at the origin. */
    private fun box(name: String, x: Float, y: Float, z: Float): File {
        val v = listOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(x, 0f, 0f), floatArrayOf(x, y, 0f), floatArrayOf(0f, y, 0f),
            floatArrayOf(0f, 0f, z), floatArrayOf(x, 0f, z), floatArrayOf(x, y, z), floatArrayOf(0f, y, z),
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
