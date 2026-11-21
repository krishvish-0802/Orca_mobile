package com.orcamobile.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.engine.jni.NativeProgressListener
import com.orcamobile.core.engine.resources.EngineResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The JNI boundary as the app uses it, on a device: the engine slices, reports
 * progress on the calling thread only (libslic3r reports from worker threads
 * too), loads a project and reads the G-code back for the preview.
 */
@RunWith(AndroidJUnit4::class)
class NativeEngineDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "engine-test").apply { mkdirs() }

    private val keys = arrayOf("printable_area", "printable_height", "layer_height", "initial_layer_print_height")
    private val values = arrayOf("0x0,220x0,220x220,0x220", "250", "0.2", "0.2")

    @Before
    fun setUp() {
        assertNull("The engine library failed to load", NativeEngine.loadError)
        EngineEnvironment(context, EngineResources(context)).ensureInitialized()
    }

    /** Records reports and whether any arrived on another thread than the caller's. */
    private class RecordingListener : NativeProgressListener {
        private val caller = Thread.currentThread()
        val messages = mutableListOf<String>()
        var foreignThread: Thread? = null

        override fun onProgress(percent: Int, message: String) {
            if (Thread.currentThread() != caller) foreignThread = Thread.currentThread()
            messages += message
        }
    }

    @Test
    fun slicesAFileAndReportsProgressOnTheCallingThread() {
        val model = cube(20f)
        val gcode = File(work, "cube.gcode")
        val listener = RecordingListener()

        val result = NativeEngine.nativeSlice(model.path, gcode.path, "", keys, values, listener, 0L)

        assertTrue(result, result.contains("\"status\":\"succeeded\""))
        assertTrue(result, result.contains("\"layerCount\":100"))
        assertNull("Progress reached Kotlin on ${listener.foreignThread}", listener.foreignThread)
        assertTrue(listener.messages.toString(), listener.messages.any { it.startsWith("Generating G-code") })
        assertTrue(gcode.length() > 10_000)
    }

    @Test
    fun slicesALoadedProjectAndPreviewsTheResult() {
        val loaded = EngineJson.loadedProject(NativeEngine.nativeProjectLoad(cube(20f).path)).getOrThrow()
        try {
            NativeEngine.nativeProjectCenterOn(loaded.handle, 110.0, 110.0)
            val gcode = File(work, "project.gcode")
            val listener = RecordingListener()

            val result = NativeEngine.nativeSliceProject(loaded.handle, gcode.path, "", keys, values, listener, 0L)
            assertTrue(result, result.contains("\"status\":\"succeeded\""))
            assertNull("Progress reached Kotlin on ${listener.foreignThread}", listener.foreignThread)

            val preview = EngineJson.loadedPreview(NativeEngine.nativePreviewLoad(gcode.path)).getOrThrow()
            try {
                assertEquals(100, preview.summary.layerZs.size)
            } finally {
                NativeEngine.nativePreviewRelease(preview.handle)
            }
        } finally {
            NativeEngine.nativeProjectRelease(loaded.handle)
        }
    }

    @Test
    fun describesOptionsAndAppliesOverrides() {
        val options = EngineJson.configOptions(
            NativeEngine.nativeDescribeOptions(arrayOf("layer_height", "sparse_infill_pattern", "no_such_option")),
        ).getOrThrow()
        assertEquals(listOf("layer_height", "sparse_infill_pattern"), options.map { it.key })
        assertTrue(options[1].choices.any { it.value == "gyroid" })

        val presetKeys = EngineJson.presetOptionKeys(NativeEngine.nativePresetOptionKeys()).getOrThrow()
        assertTrue(presetKeys.process.size > 300)
        assertTrue("nozzle_temperature" in presetKeys.filament)
        assertTrue("machine_start_gcode" in presetKeys.printer)
        val described = EngineJson.configOptions(NativeEngine.nativeDescribeOptions(presetKeys.process.toTypedArray())).getOrThrow()
        assertTrue(described.size > 300)

        val defaults = EngineJson.configValues(NativeEngine.nativeConfigValues("", arrayOf("layer_height"))).getOrThrow()
        assertTrue(defaults.containsKey("layer_height"))

        // The editor's changes travel as overrides: 0.1 mm layers double the cube's layer count.
        val gcode = File(work, "fine.gcode")
        val fine = arrayOf("0x0,220x0,220x220,0x220", "250", "0.1", "0.2")
        val result = NativeEngine.nativeSlice(cube(20f).path, gcode.path, "", keys, fine, RecordingListener(), 0L)
        assertTrue(result, result.contains("\"layerCount\":199"))
    }

    /** An ASCII STL cube of [size] mm, its corner at the origin. */
    private fun cube(size: Float): File {
        val v = listOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(size, 0f, 0f), floatArrayOf(size, size, 0f), floatArrayOf(0f, size, 0f),
            floatArrayOf(0f, 0f, size), floatArrayOf(size, 0f, size), floatArrayOf(size, size, size), floatArrayOf(0f, size, size),
        )
        // Outward-facing triangles, two per side.
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
