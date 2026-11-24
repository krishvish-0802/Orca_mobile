package com.orcamobile.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.engine.jni.NativeProgressListener
import com.orcamobile.core.engine.resources.EngineResources
import com.orcamobile.core.model.ObjectEdit
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
 * The desktop's painted modifier on a device: paint on a part makes a modifier
 * with settings of its own, and the slice follows them.
 */
@RunWith(AndroidJUnit4::class)
class NativePaintedModifierDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "painted-modifier-test").apply { mkdirs() }
    private val keys = arrayOf("printable_area", "printable_height", "layer_height", "initial_layer_print_height", "sparse_infill_density")
    private val values = arrayOf("0x0,220x0,220x220,0x220", "250", "0.2", "0.2", "10%")
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

    private fun filament(name: String): Double {
        val gcode = File(work, "$name.gcode")
        val result = EngineJson.sliceResult(NativeEngine.nativeSliceProject(handle, gcode.path, "", keys, values, Quiet, 0L), gcode.path)
        assertTrue("$result", result is SliceResult.Succeeded)
        return (result as SliceResult.Succeeded).statistics.filamentUsedMm
    }

    private fun edit(edit: ObjectEdit) =
        EngineJson.objectUpdate(NativeEngine.nativeObjectEdit(handle, 0, EngineJson.encodeObjectEdit(edit))).getOrThrow()

    @Test
    fun paintMakesAModifierWhoseSettingsPrint() {
        handle = EngineJson.loadedProject(NativeEngine.nativeProjectLoad(cube().path)).getOrThrow().handle
        NativeEngine.nativeProjectCenterOn(handle, 110.0, 110.0)
        val sparse = filament("sparse")

        // Painting the part (kind 4) makes a painted modifier on it; it adds no shell to the plate.
        assertTrue(NativeEngine.nativePaintEverything(handle, 0, 4, 1))
        val made = edit(ObjectEdit.Rename(null, "cube")).details
        assertEquals(2, made.volumes.size)
        assertEquals(VolumeKind.PAINTED_MODIFIER, made.volumes[1].kind)
        assertEquals(0.0, made.volumes[1].paintedDepthMm, 0.0)

        // Solid inside it, across the part: much more filament.
        edit(ObjectEdit.SetSetting(SettingTarget(volume = 1), "sparse_infill_density", "100%"))
        val solid = filament("solid")
        assertTrue("$solid against $sparse", solid > sparse * 1.5)

        // Only 1 mm in from the painted surface: less than solid all through.
        val shallow = edit(ObjectEdit.SetPaintedDepth(1, 1.0)).details
        assertEquals(1.0, shallow.volumes[1].paintedDepthMm, 0.01)
        val skin = filament("skin")
        assertTrue("$skin between $sparse and $solid", skin < solid)
    }

    private object Quiet : NativeProgressListener {
        override fun onProgress(percent: Int, message: String) = Unit
    }

    /** An ASCII STL cube of 30 mm, its corner at the origin. */
    private fun cube(): File {
        val size = 30f
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
            appendLine("solid cube")
            for (f in faces) {
                appendLine("facet normal 0 0 0")
                appendLine("outer loop")
                for (i in f) appendLine("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}")
                appendLine("endloop")
                appendLine("endfacet")
            }
            appendLine("endsolid cube")
        }
        return File(work, "cube.stl").apply { writeText(text) }
    }
}
