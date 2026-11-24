package com.orcamobile.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.engine.jni.NativeProgressListener
import com.orcamobile.core.engine.resources.EngineResources
import com.orcamobile.core.model.EmbossSource
import com.orcamobile.core.model.FeaturePick
import com.orcamobile.core.model.VolumeKind
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Texts that stay editable, as the desktop's Emboss tool keeps them: read back,
 * changed in place, moved to another face, made to follow the surface.
 */
@RunWith(AndroidJUnit4::class)
class NativeEmbossDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "emboss-test").apply { mkdirs() }
    private val keys = arrayOf("printable_area", "printable_height", "layer_height", "initial_layer_print_height")
    private val values = arrayOf("0x0,220x0,220x220,0x220", "250", "0.2", "0.2")
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

    private val font: String
        get() = listOf("/system/fonts/Roboto-Regular.ttf", "/system/fonts/DroidSans.ttf")
            .firstOrNull { File(it).canRead() } ?: File("/system/fonts").listFiles()!!.first { it.extension == "ttf" }.path

    @Test
    fun aTextIsReadBackChangedMovedAndWrapped() {
        handle = EngineJson.loadedProject(NativeEngine.nativeProjectLoad(cube().path)).getOrThrow().handle
        NativeEngine.nativeProjectCenterOn(handle, 110.0, 110.0)
        val text = EmbossSource(text = "Orca", fontPath = font, letterHeightMm = 6.0, depthMm = 1.0)

        // On the top (triangles 2 and 3 face up), raised.
        val top = FeaturePick(0, 0, 0, 2, listOf(0.0, 0.0, 10.0))
        val placed = EngineJson.plateUpdate(
            NativeEngine.nativeEmboss(
                handle,
                buildJsonObject {
                    put("source", ObjectToolsJson.source(text))
                    put("pick", MeshToolsJson.pick(top))
                    put("kind", 0)
                }.toString(),
            ),
        ).getOrThrow().placed
        assertEquals(1, placed)

        val read = requireNotNull(ObjectToolsJson.embossed(NativeEngine.nativeEmbossed(handle, 0, 1)).getOrThrow())
        assertEquals("Orca", read.source.text)
        assertEquals(font, read.source.fontPath)
        assertEquals(6.0, read.source.letterHeightMm, 0.01)
        assertEquals(VolumeKind.PART, read.kind)
        assertTrue(!read.useSurface)
        assertNull("the cube itself is no text", ObjectToolsJson.embossed(NativeEngine.nativeEmbossed(handle, 0, 0)).getOrThrow())
        assertTrue(details().volumes[1].embossed)

        // New words, engraved instead: the same volume, wider.
        val longer = text.copy(text = "Orca Mobile")
        EngineJson.plateUpdate(NativeEngine.nativeEmbossUpdate(handle, 0, 1, update(longer, 1, false))).getOrThrow()
        val engraved = requireNotNull(ObjectToolsJson.embossed(NativeEngine.nativeEmbossed(handle, 0, 1)).getOrThrow())
        assertEquals("Orca Mobile", engraved.source.text)
        assertEquals(VolumeKind.NEGATIVE_VOLUME, engraved.kind)
        assertTrue("${engraved.widthMm} > ${read.widthMm}", engraved.widthMm > read.widthMm)
        assertEquals(2, details().volumes.size)

        // Moved to the front face (triangle 4, y = -10).
        val front = FeaturePick(0, 0, 0, 4, listOf(0.0, -10.0, 5.0))
        EngineJson.plateUpdate(
            NativeEngine.nativeEmbossMove(handle, 1, buildJsonObject { put("pick", MeshToolsJson.pick(front)) }.toString()),
        ).getOrThrow()
        val at = details().volumes[1].transform.position
        assertEquals(-10.0, at.y, 0.2)

        // Following the surface there, and still slicing.
        EngineJson.plateUpdate(NativeEngine.nativeEmbossUpdate(handle, 0, 1, update(longer, 1, true))).getOrThrow()
        assertTrue(requireNotNull(ObjectToolsJson.embossed(NativeEngine.nativeEmbossed(handle, 0, 1)).getOrThrow()).useSurface)
        val gcode = File(work, "text.gcode")
        val result = NativeEngine.nativeSliceProject(handle, gcode.path, "", keys, values, Quiet, 0L)
        assertTrue(result, result.contains("\"status\":\"succeeded\""))
    }

    private fun update(source: EmbossSource, kind: Int, surface: Boolean) = buildJsonObject {
        put("source", ObjectToolsJson.source(source))
        put("kind", kind)
        put("surface", surface)
    }.toString()

    private fun details() = EngineJson.objectUpdate(NativeEngine.nativeObjectDetails(handle, 0)).getOrThrow().details

    private object Quiet : NativeProgressListener {
        override fun onProgress(percent: Int, message: String) = Unit
    }

    /** An ASCII STL cube of 20 mm, its corner at the origin. */
    private fun cube(): File {
        val size = 20f
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
