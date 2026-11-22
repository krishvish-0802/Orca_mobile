package com.orcamobile.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.engine.jni.NativeProgressListener
import com.orcamobile.core.engine.resources.EngineResources
import com.orcamobile.core.model.ArrangeOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The plate tools on a device, through the JNI calls the app makes: copies,
 * arrange, fill bed, auto-orient and delete, on a generic 220 mm bed.
 */
@RunWith(AndroidJUnit4::class)
class NativePlateToolsDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "plate-test").apply { mkdirs() }

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

    @Test
    fun copiesAreArrangedOnTheBedAndSlice() {
        load(box("cube", 20f, 20f, 20f))

        val copied = EngineJson.plateUpdate(
            NativeEngine.nativePlateAddCopies(handle, 0, 0, 3, "", keys, values, options),
        ).getOrThrow()
        assertEquals(3, copied.placed)
        assertEquals(4, copied.objects.single().instances)

        val arranged = EngineJson.plateUpdate(NativeEngine.nativePlateArrange(handle, "", keys, values, options)).getOrThrow()
        assertEquals(4, arranged.placed)
        assertEquals(0, arranged.notPlaced)

        // Overlapping or off-bed copies would fail validation.
        val gcode = File(work, "copies.gcode")
        val result = NativeEngine.nativeSliceProject(handle, gcode.path, "", keys, values, Quiet, 0L)
        assertTrue(result, result.contains("\"status\":\"succeeded\""))
    }

    @Test
    fun fillBedAddsCopiesUntilTheBedIsFull() {
        load(box("cube", 20f, 20f, 20f))
        val filled = EngineJson.plateUpdate(
            NativeEngine.nativePlateAddCopies(handle, 0, 0, -1, "", keys, values, options),
        ).getOrThrow()
        // 220 x 220 holds about 49 cubes of 20 mm with gaps; never more than fit by area.
        assertTrue("placed ${filled.placed}", filled.placed in 20..120)
        assertEquals(filled.placed + 1, filled.objects.single().instances)
    }

    @Test
    fun arrangeReportsWhatDoesNotFit() {
        load(box("slab", 150f, 150f, 5f))
        EngineJson.plateUpdate(NativeEngine.nativePlateAddCopies(handle, 0, 0, 1, "", keys, values, options)).getOrThrow()
        val arranged = EngineJson.plateUpdate(NativeEngine.nativePlateArrange(handle, "", keys, values, options)).getOrThrow()
        assertEquals(1, arranged.placed)
        assertEquals(1, arranged.notPlaced)
    }

    @Test
    fun orientLaysAPlateStoodOnItsEdgeFlat() {
        load(box("plate", 40f, 2f, 40f))
        val oriented = EngineJson.plateUpdate(NativeEngine.nativePlateOrient(handle, 0, 0, "", keys, values)).getOrThrow()
        val size = oriented.objects.single().sizeMm
        assertTrue("size after orient $size", size[2] < 10.0)
    }

    @Test
    fun deletingTheLastInstanceRemovesTheObject() {
        load(box("cube", 20f, 20f, 20f))
        EngineJson.plateUpdate(NativeEngine.nativePlateAddCopies(handle, 0, 0, 1, "", keys, values, options)).getOrThrow()
        assertEquals(1, EngineJson.plateUpdate(NativeEngine.nativeProjectRemove(handle, 0, 1)).getOrThrow().objects.single().instances)
        assertEquals(emptyList<Any>(), EngineJson.plateUpdate(NativeEngine.nativeProjectRemove(handle, 0, 0)).getOrThrow().objects)
        assertTrue(EngineJson.plateUpdate(NativeEngine.nativeProjectRemove(handle, 0, 0)).isFailure)
    }

    @Test
    fun transformEditsKeepTheObjectOnTheBed() {
        load(box("block", 40f, 10f, 20f))
        fun edit(json: String) = EngineJson.transformUpdate(NativeEngine.nativeInstanceTransform(handle, 0, 0, json)).getOrThrow()

        val start = edit("").transform
        assertEquals(110.0, start.position.x, 0.01)
        assertEquals(10.0, start.position.z, 0.01)   // the box's center, half its height up
        assertEquals(40.0, start.size.x, 0.01)

        val doubled = edit("""{"scale":[2,2,2]}""")
        assertEquals(80.0, doubled.transform.size.x, 0.01)
        assertEquals(20.0, doubled.transform.position.z, 0.01)
        assertEquals(40.0, doubled.objects.single().sizeMm[2], 0.01)

        // Standing it up on its end: the bed box swaps Y and Z, and it still sits on the bed.
        val turned = edit("""{"rotation":[90,0,0]}""")
        assertEquals(90.0, turned.transform.rotationDegrees.x, 0.01)
        assertEquals(20.0, turned.objects.single().sizeMm[2], 0.01)
        assertEquals(10.0, turned.transform.position.z, 0.01)

        val moved = edit("""{"position":[50,60]}""").transform
        assertEquals(50.0, moved.position.x, 0.01)
        assertEquals(60.0, moved.position.y, 0.01)

        val mirrored = edit("""{"mirror":0}""").transform
        assertTrue(com.orcamobile.core.model.Axis.X in mirrored.mirrored)

        val reset = edit("""{"reset":true}""").transform
        assertEquals(0.0, reset.rotationDegrees.x, 0.01)
        assertEquals(1.0, reset.scale.x, 0.0001)
        assertTrue(reset.mirrored.isEmpty())
        assertEquals(10.0, reset.position.z, 0.01)

        assertTrue(EngineJson.transformUpdate(NativeEngine.nativeInstanceTransform(handle, 0, 0, """{"scale":[0,1,1]}""")).isFailure)
        assertTrue(EngineJson.transformUpdate(NativeEngine.nativeInstanceTransform(handle, 0, 5, "")).isFailure)

        // Still printable after all that.
        val gcode = File(work, "block.gcode")
        val result = NativeEngine.nativeSliceProject(handle, gcode.path, "", keys, values, Quiet, 0L)
        assertTrue(result, result.contains("\"status\":\"succeeded\""))
    }

    @Test
    fun objectListEditsChangeWhatIsSliced() {
        load(box("cube", 20f, 20f, 20f))
        fun edit(json: String) = EngineJson.objectUpdate(NativeEngine.nativeObjectEdit(handle, 0, json))
        fun slice(name: String): String {
            val gcode = File(work, "$name.gcode")
            return NativeEngine.nativeSliceProject(handle, gcode.path, "", keys, values, Quiet, 0L)
        }
        val plain = slice("plain")
        assertTrue(plain, plain.contains("\"layerCount\":100"))

        // A height range with finer layers adds layers.
        val ranged = edit("""{"addRange":{"min":0,"max":4,"layerHeight":"0.1"}}""").getOrThrow()
        assertEquals("0.1", ranged.details.layerRanges.single().settings["layer_height"])
        val fine = slice("fine")
        assertTrue(fine, fine.contains("\"status\":\"succeeded\""))
        assertTrue(fine, !fine.contains("\"layerCount\":100"))
        assertTrue(edit("""{"addRange":{"min":3,"max":5,"layerHeight":"0.1"}}""").isFailure)   // overlaps
        edit("""{"removeRange":{"range":0}}""").getOrThrow()

        // A modifier with its own infill, and a negative volume.
        val modified = edit("""{"addPrimitive":{"kind":2,"shape":0,"size":10}}""").getOrThrow()
        assertEquals(com.orcamobile.core.model.VolumeKind.MODIFIER, modified.details.volumes[1].kind)
        edit("""{"set":{"volume":1,"key":"sparse_infill_density","value":"80%"}}""").getOrThrow()
        assertTrue(edit("""{"set":{"volume":1,"key":"printable_height","value":"10"}}""").isFailure)   // not a part setting
        val negative = edit("""{"addPrimitive":{"kind":1,"shape":1,"size":8}}""").getOrThrow()
        assertEquals(3, negative.details.volumes.size)
        edit("""{"set":{"key":"wall_loops","value":"4"}}""").getOrThrow()
        val withVolumes = slice("volumes")
        assertTrue(withVolumes, withVolumes.contains("\"status\":\"succeeded\""))

        // An object keeps at least one part.
        assertTrue(edit("""{"removeVolume":{"volume":0}}""").isFailure)
        assertTrue(edit("""{"setKind":{"volume":0,"kind":2}}""").isFailure)
        val trimmed = edit("""{"removeVolume":{"volume":2}}""").getOrThrow()
        assertEquals(2, trimmed.details.volumes.size)
        assertEquals("4", trimmed.details.settings["wall_loops"])
        assertTrue(EngineJson.stringList(NativeEngine.nativeSettingKeys(2)).getOrThrow().first() == "layer_height")
    }

    @Test
    fun paintedFuzzySkinChangesTheGcode() {
        load(box("cube", 20f, 20f, 20f))
        val plain = File(work, "plain.gcode")
        assertTrue(NativeEngine.nativeSliceProject(handle, plain.path, "", keys, values, Quiet, 0L).contains("\"status\":\"succeeded\""))

        assertTrue(NativeEngine.nativePaintEverything(handle, 0, 2, 1))       // fuzzy skin everywhere
        assertTrue(!NativeEngine.nativePaintEverything(handle, 0, 2, 1))      // nothing new the second time
        val fuzzy = File(work, "fuzzy.gcode")
        assertTrue(NativeEngine.nativeSliceProject(handle, fuzzy.path, "", keys, values, Quiet, 0L).contains("\"status\":\"succeeded\""))
        assertTrue("plain ${plain.length()} fuzzy ${fuzzy.length()}", fuzzy.length() > plain.length() * 1.2)

        // Painted seam and support data are kept too, and slicing still works.
        assertTrue(NativeEngine.nativePaintEverything(handle, 0, 1, 2))       // seam blocker everywhere
        assertTrue(NativeEngine.nativePaintEverything(handle, 0, 0, 2))       // support blocker everywhere
        val blocked = File(work, "blocked.gcode")
        assertTrue(NativeEngine.nativeSliceProject(handle, blocked.path, "", keys, values, Quiet, 0L).contains("\"status\":\"succeeded\""))
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
