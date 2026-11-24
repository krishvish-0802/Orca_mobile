package com.orcamobile.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.engine.jni.NativeProgressListener
import com.orcamobile.core.engine.resources.EngineResources
import com.orcamobile.core.model.ObjectEdit
import com.orcamobile.core.model.SliceResult
import com.orcamobile.core.model.TextureLayerSettings
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The desktop's texture displacement on a device: a library texture painted
 * over a cube bakes into relief that slices, and the layer stays for another bake.
 */
@RunWith(AndroidJUnit4::class)
class NativeTextureDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "texture-test").apply { mkdirs() }
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

    private fun stack(request: String) = TextureJson.stack(NativeEngine.nativeTextureStack(handle, 0, request)).getOrThrow()

    private fun size() = EngineJson.objectUpdate(NativeEngine.nativeObjectEdit(handle, 0, EngineJson.encodeObjectEdit(ObjectEdit.Rename(null, "cube"))))
        .getOrThrow().objects[0]

    @Test
    fun aPaintedTextureBakesIntoReliefThatSlices() {
        val library = TextureJson.library(NativeEngine.nativeTextureLibrary()).getOrThrow()
        assertTrue("The texture library was not packed: $library", library.size >= 40)
        val knurl = library.first { it.name == "Knurl" }

        handle = EngineJson.loadedProject(NativeEngine.nativeProjectLoad(cube().path)).getOrThrow().handle
        NativeEngine.nativeProjectCenterOn(handle, 110.0, 110.0)
        assertTrue(stack(TextureJson.request("get")).first.layers.isEmpty())

        // Nothing painted: nothing to bake.
        val (added, slot) = stack(TextureJson.request("add") { put("path", knurl.path); put("name", knurl.name) })
        assertEquals(0, slot)
        val layer = added.layers.single()
        assertEquals("Knurl", layer.name)
        assertTrue(layer.widthPx > 0)
        assertFalse(layer.painted)
        assertTrue(NativeEngine.nativeTextureBake(handle, 0, Quiet, 0L).contains("error"))

        // 1 mm deep, 6 mm tiles, over the whole cube.
        val settings = TextureLayerSettings(depthMm = 1f, tileMm = 6f)
        val set = stack(TextureJson.request("set", 0) { put("settings", TextureJson.settings(settings)) }).first.layers.single()
        assertEquals(1f, set.settings.depthMm, 1e-4f)
        assertEquals(6f, set.settings.tileMm, 1e-4f)
        assertTrue(stack(TextureJson.request("paintAll", 0) { put("erase", false) }).first.layers.single().painted)

        val before = size()
        var progress = 0
        val baked = TextureJson.bake(NativeEngine.nativeTextureBake(handle, 0, { percent, _ -> progress = maxOf(progress, percent) }, 0L))
            .getOrThrow()
        assertNotNull(baked)
        assertEquals(1, baked!!.parts)
        assertEquals(12L, baked.trianglesBefore)
        assertTrue("${baked.trianglesAfter} triangles", baked.trianglesAfter > 1000)
        assertTrue("progress $progress", progress > 0)
        // The relief stands out of the faces, up to the depth.
        val after = size()
        assertTrue("${after.sizeMm} against ${before.sizeMm}", after.sizeMm[0] > before.sizeMm[0] + 0.3)
        assertTrue("${after.sizeMm}", after.sizeMm[0] < before.sizeMm[0] + 2 * settings.depthMm + 0.1)
        assertTrue(after.triangles > 1000)

        // The layer stays for another bake; its paint is used up.
        assertFalse(stack(TextureJson.request("get")).first.layers.single().painted)

        val gcode = File(work, "textured.gcode")
        val keys = arrayOf("printable_area", "printable_height", "layer_height", "initial_layer_print_height")
        val values = arrayOf("0x0,220x0,220x220,0x220", "250", "0.2", "0.2")
        val result = EngineJson.sliceResult(NativeEngine.nativeSliceProject(handle, gcode.path, "", keys, values, Quiet, 0L), gcode.path)
        assertTrue("$result", result is SliceResult.Succeeded)

        assertTrue(stack(TextureJson.request("remove", 0)).first.layers.isEmpty())
    }

    @Test
    fun aStoppedBakeChangesNothing() {
        handle = EngineJson.loadedProject(NativeEngine.nativeProjectLoad(cube().path)).getOrThrow().handle
        val noise = TextureJson.library(NativeEngine.nativeTextureLibrary()).getOrThrow().first { it.name == "Noise" }
        stack(TextureJson.request("add") { put("path", noise.path); put("name", noise.name) })
        stack(TextureJson.request("paintAll", 0) { put("erase", false) })
        val token = NativeEngine.nativeCreateCancelToken()
        NativeEngine.nativeRequestCancel(token)
        val payload = NativeEngine.nativeTextureBake(handle, 0, Quiet, token)
        NativeEngine.nativeReleaseCancelToken(token)
        assertNull(TextureJson.bake(payload).getOrThrow())
        assertEquals(12L, size().triangles)
        assertTrue(stack(TextureJson.request("get")).first.layers.single().painted)
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
