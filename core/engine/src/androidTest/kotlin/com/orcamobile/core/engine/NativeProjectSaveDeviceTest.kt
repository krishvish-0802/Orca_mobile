package com.orcamobile.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.engine.resources.EngineResources
import com.orcamobile.core.model.ArrangeOptions
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.PlateAction
import com.orcamobile.core.model.PresetSelection
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * Saving a project as the desktop's .3mf and opening it again, on the Elegoo
 * Centauri Carbon's presets: plates, places, and the presets and settings.
 */
@RunWith(AndroidJUnit4::class)
class NativeProjectSaveDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "project-save-test").apply { mkdirs() }
    private val vendor = "Elegoo"
    private val printer = "Elegoo Centauri Carbon 0.4 nozzle"
    private val handles = mutableListOf<Long>()

    @Before
    fun setUp() {
        assertNull("The engine library failed to load", NativeEngine.loadError)
        EngineEnvironment(context, EngineResources(context)).ensureInitialized()
    }

    @After
    fun tearDown() {
        handles.forEach(NativeEngine::nativeProjectRelease)
    }

    private fun load(path: String): Long =
        EngineJson.loadedProject(NativeEngine.nativeProjectLoad(path)).getOrThrow().handle.also { handles += it }

    private fun selection(): PresetSelection {
        val compatible = EngineJson.compatiblePresets(NativeEngine.nativeCompatiblePresets(vendor, printer)).getOrThrow()
        return PresetSelection(vendor, printer, requireNotNull(compatible.defaultProcess), listOf(requireNotNull(compatible.defaultFilament)))
    }

    private fun position(handle: Long, instance: Int) =
        EngineJson.transformUpdate(NativeEngine.nativeInstanceTransform(handle, 0, instance, "")).getOrThrow().transform.position

    @Test
    fun aSavedProjectOpensWithItsPlatesPlacesAndSettings() {
        val presets = selection()
        val presetsJson = EngineJson.encode(presets)
        val handle = load(cube().path)
        NativeEngine.nativeProjectCenterOn(handle, 128.0, 128.0)
        // A copy, on a second plate named "Spares", at (60, 60) there.
        EngineJson.plateUpdate(
            NativeEngine.nativePlateAddCopies(handle, 0, 0, 1, presetsJson, emptyArray(), emptyArray(), EngineJson.encode(ArrangeOptions())),
        ).getOrThrow()
        NativeEngine.nativePlates(handle, EngineJson.encode(PlateAction.Add))
        NativeEngine.nativePlates(handle, EngineJson.encode(PlateAction.Move(InstanceRef(0, 1), 1)))
        NativeEngine.nativePlates(handle, EngineJson.encode(PlateAction.Rename(1, "Spares")))
        NativeEngine.nativeInstanceTransform(handle, 0, 1, """{"position":[60,60]}""")

        val file = File(work, "saved.3mf").apply { delete() }
        val saved = EngineJson.parse(NativeEngine.nativeProjectSave(handle, file.path, presetsJson, arrayOf("layer_height"), arrayOf("0.12")))
        assertNull(saved.toString(), saved["error"])
        assertTrue(file.length() > 0)

        // The desktop's project settings: its presets, and the one setting changed from the process.
        val settings = ZipFile(file).use { zip ->
            val entry = requireNotNull(zip.getEntry("Metadata/project_settings.config")) { "no project settings in the file" }
            EngineJson.parse(zip.getInputStream(entry).bufferedReader().readText())
        }
        assertEquals(presets.process, settings.getValue("print_settings_id").jsonPrimitive.content)
        assertEquals(printer, settings.getValue("printer_settings_id").jsonPrimitive.content)
        assertEquals("0.12", settings.getValue("layer_height").jsonPrimitive.content)
        val processChanges = settings.getValue("different_settings_to_system").jsonArray[0].jsonPrimitive.content
        assertTrue(processChanges, "layer_height" in processChanges.split(';'))

        // Opened again: both plates, the copy where it was on its plate.
        val reopened = load(file.path)
        val plates = EngineJson.plates(NativeEngine.nativePlates(reopened, EngineJson.encode(PlateAction.Get))).getOrThrow()
        assertEquals(listOf("", "Spares"), plates.plates.map { it.name })
        assertEquals(listOf(0, 1), plates.objects.single().plates)
        val copy = position(reopened, 1)
        assertEquals(60.0, copy.x, 0.5)
        assertEquals(60.0, copy.y, 0.5)

        // And the presets it was saved with, with the change.
        val restored = EngineJson.projectPresets(NativeEngine.nativeProjectPresets(reopened, vendor))
        assertEquals(presets.copy(filamentColours = restored?.selection?.filamentColours.orEmpty()), restored?.selection)
        assertEquals(mapOf("layer_height" to "0.12"), restored?.changes)
    }

    /** An ASCII STL cube of 20 mm, its corner at the origin. */
    private fun cube(): File {
        val s = 20f
        val v = listOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(s, 0f, 0f), floatArrayOf(s, s, 0f), floatArrayOf(0f, s, 0f),
            floatArrayOf(0f, 0f, s), floatArrayOf(s, 0f, s), floatArrayOf(s, s, s), floatArrayOf(0f, s, s),
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
