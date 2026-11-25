package com.orcamobile.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.engine.resources.EngineResources
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The desktop's preset files, written and read by its own code, on the Elegoo Centauri Carbon's presets. */
@RunWith(AndroidJUnit4::class)
class NativePresetFilesDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "preset-files-test").apply { mkdirs() }
    private val vendor = "Elegoo"
    private val printer = "Elegoo Centauri Carbon 0.4 nozzle"

    @Before
    fun setUp() {
        assertNull("The engine library failed to load", NativeEngine.loadError)
        EngineEnvironment(context, EngineResources(context)).ensureInitialized()
    }

    private fun defaultProcess(): String {
        val compatible = EngineJson.compatiblePresets(NativeEngine.nativeCompatiblePresets(vendor, printer)).getOrThrow()
        return requireNotNull(compatible.defaultProcess)
    }

    @Test
    fun aUserPresetWrittenAsTheDesktopDoesComesBackWithWhatItChanges() {
        val process = defaultProcess()
        val written = EngineJson.parse(
            NativeEngine.nativeUserPresetJson(
                """{"kind":1,"name":"My fine","inherits":"$process","values":{"layer_height":"0.12","wall_loops":"5"}}""",
            ),
        )
        val text = written.getValue("json").jsonPrimitive.content
        // The desktop's own header and settings id, which its Import configs looks for.
        val file = EngineJson.parse(text)
        assertEquals("My fine", file.getValue("name").jsonPrimitive.content)
        assertEquals("User", file.getValue("from").jsonPrimitive.content)
        assertEquals(process, file.getValue("inherits").jsonPrimitive.content)
        assertEquals("My fine", file.getValue("print_settings_id").jsonPrimitive.content)

        val path = File(work, "my-fine.json").apply { writeText(text) }.absolutePath
        val read = EngineJson.parse(NativeEngine.nativeReadPresetFile(vendor, path))
        assertTrue(read.toString(), read.getValue("problems").jsonArray.isEmpty())
        val preset = read.getValue("presets").jsonArray.single().jsonObject
        assertEquals(1, preset.getValue("kind").jsonPrimitive.int)
        assertEquals("My fine", preset.getValue("name").jsonPrimitive.content)
        assertEquals(process, preset.getValue("inherits").jsonPrimitive.content)
        val values = preset.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(mapOf("layer_height" to "0.12", "wall_loops" to "5"), values)
    }

    @Test
    fun aPresetBasedOnAnotherMakersIsLeftOutSayingWhy() {
        val text = """{"name":"Borrowed","from":"User","version":"2.3.0.0","inherits":"Some Other Printer Process","print_settings_id":"Borrowed","layer_height":"0.1"}"""
        val path = File(work, "borrowed.json").apply { writeText(text) }.absolutePath
        val read = EngineJson.parse(NativeEngine.nativeReadPresetFile(vendor, path))
        assertTrue(read.getValue("presets").jsonArray.isEmpty())
        assertTrue(read.toString(), read.getValue("problems").jsonArray.single().jsonPrimitive.content.contains("Some Other Printer Process"))
    }
}
