package com.orcamobile.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.engine.jni.NativeProgressListener
import com.orcamobile.core.engine.resources.EngineResources
import com.orcamobile.core.model.ArrangeOptions
import com.orcamobile.core.model.BrimEar
import com.orcamobile.core.model.EmbossSource
import com.orcamobile.core.model.LayerBrush
import com.orcamobile.core.model.LayerProfileAction
import com.orcamobile.core.model.PlateConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.orcamobile.core.model.Axes
import com.orcamobile.core.model.BooleanOperation
import com.orcamobile.core.model.ObjectEdit
import com.orcamobile.core.model.PrimitiveShape
import com.orcamobile.core.model.SimplifySettings
import com.orcamobile.core.model.VolumeKind
import com.orcamobile.core.model.ConnectorProblem
import com.orcamobile.core.model.CutConnector
import com.orcamobile.core.model.CutConnectorType
import com.orcamobile.core.model.CutMode
import com.orcamobile.core.model.CutPlane
import com.orcamobile.core.model.CutRequest
import com.orcamobile.core.model.AssemblyStep
import com.orcamobile.core.model.FeatureFilter
import com.orcamobile.core.model.FeaturePick
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.MeasureRequest
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

        // As the desktop's "Enable painted fuzzy skin for this object": the default disables it.
        EngineJson.objectUpdate(NativeEngine.nativeObjectEdit(handle, 0, """{"set":{"key":"fuzzy_skin","value":"none"}}""")).getOrThrow()
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

    @Test
    fun splitsSeparatePiecesIntoObjectsOrParts() {
        load(twoCubes())
        // Into parts of the same object first...
        val parts = EngineJson.objectUpdate(NativeEngine.nativeObjectEdit(handle, 0, """{"splitToParts":{"volume":0}}""")).getOrThrow()
        assertEquals(2, parts.details.partCount)
        // ...then each part into an object of its own, where it lies.
        val split = EngineJson.plateUpdate(NativeEngine.nativePlateSplit(handle, 0)).getOrThrow()
        assertEquals(2, split.placed)
        assertEquals(2, split.objects.size)
        assertTrue(split.objects.all { it.sizeMm[0] < 11.0 })
        // A single piece does not split.
        assertTrue(EngineJson.plateUpdate(NativeEngine.nativePlateSplit(handle, 0)).isFailure)
        val gcode = File(work, "split.gcode")
        assertTrue(NativeEngine.nativeSliceProject(handle, gcode.path, "", keys, values, Quiet, 0L).contains("\"status\":\"succeeded\""))
    }

    @Test
    fun cutsWithAPlaneConnectorsAndADovetail() {
        load(box("cube", 20f, 20f, 20f))
        val at = InstanceRef(0, 0)
        fun check(request: CutRequest) = MeshToolsJson.cutCheck(NativeEngine.nativeCutPreview(handle, MeshToolsJson.encode(request))).getOrThrow()
        fun cut(request: CutRequest) = EngineJson.plateUpdate(NativeEngine.nativeCutPerform(handle, MeshToolsJson.encode(request)))
        fun slices(name: String) =
            NativeEngine.nativeSliceProject(handle, File(work, "$name.gcode").path, "", keys, values, Quiet, 0L).contains("\"status\":\"succeeded\"")

        val level = CutRequest(at, CutPlane(10.0))
        val checked = check(level)
        assertTrue(checked.reason, checked.canCut)
        assertEquals(20.0, checked.heightRange.endInclusive, 0.01)
        // A plane above the object, or a connector off the cut face, cannot cut.
        assertTrue(!check(CutRequest(at, CutPlane(30.0))).canCut)
        val offFace = check(level.copy(connectors = listOf(CutConnector(u = 40.0))))
        assertEquals(listOf(ConnectorProblem.OUTSIDE_SECTION), offFace.problems)
        assertTrue(cut(level.copy(connectors = listOf(CutConnector(u = 40.0)))).isFailure)

        // Plain: two 10 mm pieces standing where the cube was.
        val halves = cut(level).getOrThrow()
        assertEquals(2, halves.placed)
        assertTrue(halves.objects.all { kotlin.math.abs(it.sizeMm[2] - 10.0) < 0.05 })
        assertTrue(slices("halves"))

        // A plug: it stands on the lower piece, its hole is in the upper one.
        NativeEngine.nativeProjectRelease(handle)
        load(box("cube", 20f, 20f, 20f))
        val plug = cut(level.copy(connectors = listOf(CutConnector(sizeMm = 5.0, depthMm = 4.0)))).getOrThrow()
        assertEquals(2, plug.placed)
        assertTrue("sizes ${plug.objects.map { it.sizeMm }}", plug.objects.any { kotlin.math.abs(it.sizeMm[2] - 14.0) < 0.1 })
        assertTrue(slices("plug"))

        // A dowel is a pin of its own.
        NativeEngine.nativeProjectRelease(handle)
        load(box("cube", 20f, 20f, 20f))
        val dowel = cut(level.copy(connectors = listOf(CutConnector(type = CutConnectorType.DOWEL, sizeMm = 4.0, depthMm = 3.0)))).getOrThrow()
        assertEquals(3, dowel.placed)
        assertTrue(slices("dowel"))

        // A dovetail along the plane, tilted.
        NativeEngine.nativeProjectRelease(handle)
        load(box("bar", 40f, 20f, 20f))
        val dovetail = CutRequest(at, CutPlane(10.0, tiltXDegrees = 90.0), mode = CutMode.DOVETAIL)
        assertTrue(check(dovetail).reason, check(dovetail).canCut)
        assertEquals(2, cut(dovetail).getOrThrow().placed)
        assertTrue(slices("dovetail"))
    }

    @Test
    fun simplifiesAndCombinesParts() {
        load(box("cube", 20f, 20f, 20f))
        fun edit(edit: ObjectEdit) = EngineJson.objectUpdate(NativeEngine.nativeObjectEdit(handle, 0, EngineJson.encodeObjectEdit(edit))).getOrThrow()

        // A sphere part has triangles to spare.
        val withSphere = edit(ObjectEdit.AddPrimitive(VolumeKind.PART, PrimitiveShape.SPHERE, 20.0))
        val sphere = withSphere.details.volumes.lastIndex
        val before = withSphere.details.volumes[sphere].triangles
        val halve = SimplifySettings(byRatio = true, ratioPercent = 50.0)
        val counts = MeshToolsJson.simplifyCounts(
            NativeEngine.nativeSimplifyPreview(handle, 0, MeshToolsJson.encode(sphere, halve)),
        ).getOrThrow()
        assertEquals(before, counts.before)
        assertTrue("$counts", counts.after in (before / 2 - before / 10)..(before / 2 + before / 10))
        val simplified = edit(ObjectEdit.Simplify(sphere, halve))
        assertEquals(counts.after, simplified.details.volumes[sphere].triangles)

        // The sphere half inside the cube: the difference bites it out of the cube.
        edit(ObjectEdit.VolumeTransform(sphere, Axes(10.0, 0.0, 0.0), Axes(0.0, 0.0, 0.0), Axes(1.0, 1.0, 1.0)))
        val combined = edit(ObjectEdit.MeshBoolean(0, sphere, BooleanOperation.DIFFERENCE, deleteTool = true))
        assertEquals(1, combined.details.volumes.size)
        assertTrue(combined.details.volumes[0].name.endsWith("difference"))
        assertTrue(combined.details.volumes[0].triangles > 12)
        assertTrue(combined.objects.single().sizeMm[0] < 20.5)
        val gcode = File(work, "boolean.gcode")
        assertTrue(NativeEngine.nativeSliceProject(handle, gcode.path, "", keys, values, Quiet, 0L).contains("\"status\":\"succeeded\""))
        // The same part twice is refused.
        assertTrue(EngineJson.objectUpdate(
            NativeEngine.nativeObjectEdit(handle, 0, EngineJson.encodeObjectEdit(ObjectEdit.MeshBoolean(0, 0, BooleanOperation.UNION, false))),
        ).isFailure)
    }

    @Test
    fun measuresFacesAndAssemblesOneCubeAgainstTheOther() {
        load(twoCubes())
        EngineJson.objectUpdate(NativeEngine.nativeObjectEdit(handle, 0, """{"splitToParts":{"volume":0}}""")).getOrThrow()
        assertEquals(2, EngineJson.plateUpdate(NativeEngine.nativePlateSplit(handle, 0)).getOrThrow().placed)
        fun pick(objectIndex: Int, facet: Int) = FeaturePick(objectIndex, 0, 0, facet, listOf(0.0, 0.0, 0.0))
        fun measure(vararg picks: FeaturePick) = MeshToolsJson.measureOutcome(
            NativeEngine.nativeMeasure(handle, MeshToolsJson.encode(MeasureRequest(picks.toList(), FeatureFilter.PLANES))),
        ).getOrThrow()
        // The face of each cube whose normal points along `direction`.
        fun faceFacing(objectIndex: Int, direction: List<Double>): FeaturePick = (0 until 12).map { pick(objectIndex, it) }.first { p ->
            val normal = measure(p).features.single().a
            (0..2).sumOf { normal[it] * direction[it] } > 0.99
        }
        val leftRight = faceFacing(0, listOf(1.0, 0.0, 0.0)).let { a -> faceFacing(1, listOf(1.0, 0.0, 0.0)).let { b -> a to b } }
        val left = if (measure(leftRight.first).features.single().b[0] < measure(leftRight.second).features.single().b[0]) 0 else 1
        val right = 1 - left
        val leftEast = faceFacing(left, listOf(1.0, 0.0, 0.0))
        val rightWest = faceFacing(right, listOf(-1.0, 0.0, 0.0))
        val leftTop = faceFacing(left, listOf(0.0, 0.0, 1.0))

        // A face and the one beside it: 90 degrees.
        assertEquals(90.0, measure(leftEast, leftTop).measurement!!.angleDegrees!!, 0.01)
        // Facing faces 20 mm apart, on different objects.
        val apart = measure(leftEast, rightWest)
        assertTrue(apart.assembly.differentParts)
        assertTrue(apart.assembly.centerCoincidence)
        assertEquals(20.0, kotlin.math.abs(apart.assembly.parallelDistanceMm), 0.01)

        // Center coincidence: the right cube comes up against the left one.
        EngineJson.plateUpdate(
            NativeEngine.nativeAssemble(
                handle,
                MeshToolsJson.encodeAssemble(leftEast, rightWest, FeatureFilter.PLANES, AssemblyStep.CENTER_COINCIDENCE, 0.0, listOf(0.0, 0.0, 0.0)),
            ),
        ).getOrThrow()
        assertEquals(0.0, measure(leftEast, rightWest).assembly.parallelDistanceMm, 0.01)
        // Then 5 mm apart again, and a quarter turn about the face.
        EngineJson.plateUpdate(
            NativeEngine.nativeAssemble(
                handle,
                MeshToolsJson.encodeAssemble(leftEast, rightWest, FeatureFilter.PLANES, AssemblyStep.PARALLEL_DISTANCE, 5.0, listOf(0.0, 0.0, 0.0)),
            ),
        ).getOrThrow()
        assertEquals(5.0, kotlin.math.abs(measure(leftEast, rightWest).assembly.parallelDistanceMm), 0.01)
        assertTrue(
            EngineJson.plateUpdate(
                NativeEngine.nativeAssemble(
                    handle,
                    MeshToolsJson.encodeAssemble(leftEast, rightWest, FeatureFilter.PLANES, AssemblyStep.ROTATE_AROUND, 90.0, listOf(0.0, 0.0, 0.0)),
                ),
            ).isSuccess,
        )
        // Two faces of one part cannot be assembled.
        assertTrue(!measure(leftEast, leftTop).assembly.differentParts)
        val gcode = File(work, "assembled.gcode")
        assertTrue(NativeEngine.nativeSliceProject(handle, gcode.path, "", keys, values, Quiet, 0L).contains("\"status\":\"succeeded\""))
    }

    @Test
    fun variableLayerHeightsBrimEarsAndEmboss() {
        load(box("cube", 20f, 20f, 20f))
        val config = PlateConfig(null, keys.zip(values).toMap())
        fun profile(action: LayerProfileAction) = ObjectToolsJson.layerProfile(
            NativeEngine.nativeLayerProfile(
                handle, 0, "", config.overrides.keys.toTypedArray(), config.overrides.values.toTypedArray(), ObjectToolsJson.encode(action),
            ),
        ).getOrThrow()
        fun slices(name: String) =
            NativeEngine.nativeSliceProject(handle, File(work, "$name.gcode").path, "", keys, values, Quiet, 0L).contains("\"status\":\"succeeded\"")

        // 0.2 mm layers: 100 of them, and no profile of its own.
        val uniform = profile(LayerProfileAction.Get)
        assertEquals(100, uniform.layerCount)
        assertTrue(!uniform.custom)
        // Thinner layers around 10 mm make more of them.
        var brushed = uniform
        repeat(10) { brushed = profile(LayerProfileAction.Brush(10.0, 0.05, 4.0, LayerBrush.DECREASE)) }
        assertTrue(brushed.custom)
        assertTrue("${brushed.layerCount}", brushed.layerCount > uniform.layerCount)
        assertTrue(profile(LayerProfileAction.Smooth(5, false)).custom)
        assertTrue(slices("brushed"))
        assertEquals(100, profile(LayerProfileAction.Reset).layerCount)

        // Brim ears: one placed, the corners found, kept on the object.
        val at = InstanceRef(0, 0)
        fun ears(request: String) = ObjectToolsJson.ears(NativeEngine.nativeBrimEars(handle, 0, 0, request)).getOrThrow()
        val found = ears(ObjectToolsJson.encodeEarsRequest("find", maxAngle = 125.0, detection = 1.0, radius = 4.0))
        assertEquals(4, found.size)
        val kept = ears(ObjectToolsJson.encodeEarsRequest("set", found + BrimEar(110.0, 100.0, 3.0)))
        assertEquals(5, kept.size)
        assertEquals(110.0, kept.last().x, 0.01)
        assertEquals(5, ears(ObjectToolsJson.encodeEarsRequest("get")).size)
        EngineJson.objectUpdate(NativeEngine.nativeObjectEdit(handle, 0, """{"set":{"key":"brim_type","value":"painted"}}""")).getOrThrow()
        assertTrue(slices("ears"))

        // Text on the cube's top, raised; then as an object of its own; then an SVG.
        val font = listOf("/system/fonts/Roboto-Regular.ttf", "/system/fonts/DroidSans.ttf")
            .firstOrNull { File(it).canRead() } ?: File("/system/fonts").listFiles()!!.first { it.extension == "ttf" }.path
        val text = EmbossSource(text = "Orca", fontPath = font, letterHeightMm = 6.0, depthMm = 1.0)
        val size = ObjectToolsJson.embossSize(NativeEngine.nativeEmbossSize(ObjectToolsJson.source(text).toString())).getOrThrow()
        assertTrue("$size", size.first > 10 && size.second in 3.0..8.0)
        // The cube's top: its triangles 2 and 3 face up (see box()).
        val top = FeaturePick(0, 0, 0, 2, listOf(0.0, 0.0, 10.0))
        val onTop = EngineJson.plateUpdate(
            NativeEngine.nativeEmboss(
                handle,
                buildJsonObject {
                    put("source", ObjectToolsJson.source(text))
                    put("pick", MeshToolsJson.pick(top))
                    put("kind", 0)
                }.toString(),
            ),
        ).getOrThrow()
        assertEquals(1, onTop.placed)
        assertEquals(21.0, onTop.objects.single().sizeMm[2], 0.1)
        val svg = File(work, "star.svg").apply {
            writeText(
                """<svg xmlns="http://www.w3.org/2000/svg" width="20mm" height="20mm" viewBox="0 0 20 20">""" +
                    """<path d="M10 0 L13 7 L20 7 L14 12 L16 20 L10 15 L4 20 L6 12 L0 7 L7 7 Z" fill="black"/></svg>""",
            )
        }
        for (source in listOf(text, EmbossSource(svgPath = svg.path, svgWidthMm = 30.0, depthMm = 2.0))) {
            EngineJson.plateUpdate(
                NativeEngine.nativeEmboss(
                    handle,
                    buildJsonObject {
                        put("source", ObjectToolsJson.source(source))
                        put("x", 160.0)
                        put("y", 160.0)
                    }.toString(),
                ),
            ).getOrThrow()
        }
        val objects = EngineJson.projectObjects(NativeEngine.nativeProjectObjects(handle)).getOrThrow()
        assertEquals(3, objects.size)
        assertEquals(30.0, objects[2].sizeMm[0], 0.2)
        assertEquals(2.0, objects[2].sizeMm[2], 0.05)
        EngineJson.plateUpdate(NativeEngine.nativePlateArrange(handle, "", keys, values, options)).getOrThrow()
        assertTrue(slices("embossed"))
    }

    /** Two 10 mm cubes 20 mm apart in one STL. */
    private fun twoCubes(): File {
        val text = buildString {
            appendLine("solid two")
            for (dx in listOf(0f, 30f)) {
                val v = listOf(
                    floatArrayOf(dx, 0f, 0f), floatArrayOf(dx + 10, 0f, 0f), floatArrayOf(dx + 10, 10f, 0f), floatArrayOf(dx, 10f, 0f),
                    floatArrayOf(dx, 0f, 10f), floatArrayOf(dx + 10, 0f, 10f), floatArrayOf(dx + 10, 10f, 10f), floatArrayOf(dx, 10f, 10f),
                )
                val faces = listOf(
                    intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
                    intArrayOf(0, 1, 5), intArrayOf(0, 5, 4), intArrayOf(1, 2, 6), intArrayOf(1, 6, 5),
                    intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
                )
                for (f in faces) {
                    appendLine("facet normal 0 0 0")
                    appendLine("outer loop")
                    for (i in f) appendLine("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}")
                    appendLine("endloop")
                    appendLine("endfacet")
                }
            }
            appendLine("endsolid two")
        }
        return File(work, "two.stl").apply { writeText(text) }
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
