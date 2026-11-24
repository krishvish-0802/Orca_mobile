package com.orcamobile.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.engine.jni.NativeProgressListener
import com.orcamobile.core.engine.resources.EngineResources
import com.orcamobile.core.model.SliceResult
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * A cube tipped onto an edge, as turning it on the plate leaves it: without
 * supports its first layer is empty, and the slice says so (not just "Errors");
 * with supports it slices.
 */
@RunWith(AndroidJUnit4::class)
class NativeSliceErrorsDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "slice-errors-test").apply { mkdirs() }
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

    private fun slice(name: String, extra: Map<String, String>): SliceResult {
        val settings = mapOf(
            "printable_area" to "0x0,220x0,220x220,0x220",
            "printable_height" to "250",
            "layer_height" to "0.2",
            "initial_layer_print_height" to "0.2",
        ) + extra
        val gcode = File(work, "$name.gcode")
        val payload = NativeEngine.nativeSliceProject(
            handle, gcode.path, "", settings.keys.toTypedArray(), settings.values.toTypedArray(), Quiet, 0L,
        )
        return EngineJson.sliceResult(payload, gcode.path)
    }

    @Test
    fun anEmptyFirstLayerIsExplainedAndSupportsFixIt() {
        handle = EngineJson.loadedProject(NativeEngine.nativeProjectLoad(tippedCube().path)).getOrThrow().handle
        NativeEngine.nativeProjectCenterOn(handle, 110.0, 110.0)

        val bare = slice("bare", emptyMap())
        assertTrue("$bare", bare is SliceResult.Failed)
        val message = (bare as SliceResult.Failed).message
        assertTrue("The reason, not just \"Errors\": $message", message.contains("first layer", ignoreCase = true))

        val supported = slice(
            "supported",
            mapOf("enable_support" to "1", "support_type" to "normal(auto)", "support_threshold_angle" to "40"),
        )
        assertTrue("$supported", supported is SliceResult.Succeeded)
    }

    private object Quiet : NativeProgressListener {
        override fun onProgress(percent: Int, message: String) = Unit
    }

    /** A 20 mm cube turned 70° about X: it rests on an edge, one face hanging low over the bed. */
    private fun tippedCube(): File {
        val size = 20f
        val angle = Math.toRadians(70.0)
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        val corners = listOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(size, 0f, 0f), floatArrayOf(size, size, 0f), floatArrayOf(0f, size, 0f),
            floatArrayOf(0f, 0f, size), floatArrayOf(size, 0f, size), floatArrayOf(size, size, size), floatArrayOf(0f, size, size),
        ).map { (x, y, z) -> floatArrayOf(x, y * c - z * s, y * s + z * c) }
        val faces = listOf(
            intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
            intArrayOf(0, 1, 5), intArrayOf(0, 5, 4), intArrayOf(1, 2, 6), intArrayOf(1, 6, 5),
            intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
        )
        val text = buildString {
            appendLine("solid tipped")
            for (f in faces) {
                appendLine("facet normal 0 0 0")
                appendLine("outer loop")
                for (i in f) appendLine("vertex ${corners[i][0]} ${corners[i][1]} ${corners[i][2]}")
                appendLine("endloop")
                appendLine("endfacet")
            }
            appendLine("endsolid tipped")
        }
        return File(work, "tipped.stl").apply { writeText(text) }
    }
}
