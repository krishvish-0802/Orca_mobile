package com.orcamobile.feature.prepare

import com.orcamobile.core.domain.model.TextureImageRepository
import com.orcamobile.core.domain.project.ObjectEditor
import com.orcamobile.core.domain.project.TextureTools
import com.orcamobile.core.model.LibraryTexture
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.ObjectDetails
import com.orcamobile.core.model.ObjectEdit
import com.orcamobile.core.model.ObjectUpdate
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.SettingScope
import com.orcamobile.core.model.TextureBake
import com.orcamobile.core.model.TextureLayer
import com.orcamobile.core.model.TextureLayerSettings
import com.orcamobile.core.model.TextureStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextureViewModelTest {

    private val project = ProjectId("p")
    private val target = TextureTarget(project, 0)
    private val dispatcher = UnconfinedTestDispatcher()

    /** A stack kept in memory, as the engine keeps it. */
    private class FakeTextures : TextureTools {
        var stack = TextureStack()
        val writes = mutableListOf<TextureLayerSettings>()
        var baked = 0

        private fun layer(slot: Int, path: String, name: String) =
            TextureLayer(slot, name, path, 512, 512, hasColour = false, painted = false, settings = TextureLayerSettings())

        private fun change(f: (List<TextureLayer>) -> List<TextureLayer>) = Result.success(stack.copy(layers = f(stack.layers)).also { stack = it })

        override suspend fun stack(project: ProjectId, objectIndex: Int) = Result.success(stack)

        override suspend fun addLayer(project: ProjectId, objectIndex: Int, imagePath: String, name: String): Result<Pair<TextureStack, Int>> {
            val slot = (0 until TextureStack.MAX_LAYERS).first { s -> stack.layers.none { it.slot == s } }
            return change { it + layer(slot, imagePath, name) }.map { it to slot }
        }

        override suspend fun setImage(project: ProjectId, objectIndex: Int, slot: Int, imagePath: String, name: String) =
            change { layers -> layers.map { if (it.slot == slot) it.copy(path = imagePath, name = name) else it } }

        override suspend fun setLayer(project: ProjectId, objectIndex: Int, slot: Int, settings: TextureLayerSettings): Result<TextureStack> {
            writes += settings
            return change { layers -> layers.map { if (it.slot == slot) it.copy(settings = settings) else it } }
        }

        override suspend fun removeLayer(project: ProjectId, objectIndex: Int, slot: Int) = change { layers -> layers.filter { it.slot != slot } }

        override suspend fun moveLayer(project: ProjectId, objectIndex: Int, slot: Int, by: Int) = Result.success(stack)

        override suspend fun setOptions(project: ProjectId, objectIndex: Int, smooth: Boolean, smoothStrength: Float, smoothPasses: Int, displaceBorder: Boolean) =
            Result.success(stack.copy(smooth = smooth).also { stack = it })

        override suspend fun paintAll(project: ProjectId, objectIndex: Int, slot: Int, erase: Boolean) =
            change { layers -> layers.map { if (it.slot == slot) it.copy(painted = !erase) else it } }

        override suspend fun bake(project: ProjectId, objectIndex: Int, onProgress: (Int) -> Unit): Result<TextureBake> {
            onProgress(50)
            baked++
            stack = stack.copy(layers = stack.layers.map { it.copy(painted = false) })
            return Result.success(TextureBake(1, 12, 40_000, budgetLimited = false))
        }

        override suspend fun library() = Result.success(listOf(LibraryTexture("Knurl", "/res/Knurl.png"), LibraryTexture("Noise", "/res/Noise.png")))
    }

    private class FakeImages : TextureImageRepository {
        override suspend fun import(uri: String, maxSidePx: Int) = Result.success("/files/photo.png" to "photo")
    }

    private class FakeObjects : ObjectEditor {
        override suspend fun details(project: ProjectId, objectIndex: Int) =
            Result.success(ObjectUpdate(ObjectDetails("cube", emptyMap(), emptyList(), emptyList()), listOf(ModelObjectInfo("cube", 1, 40_000, listOf(31.0, 31.0, 31.0)))))

        override suspend fun edit(project: ProjectId, objectIndex: Int, edit: ObjectEdit): Result<ObjectUpdate> = error("not used")

        override suspend fun settingKeys(scope: SettingScope) = Result.success(emptyList<String>())
    }

    private val textures = FakeTextures()
    private val vm = TextureViewModel(textures, FakeImages(), FakeObjects())

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a library texture becomes the active layer`() = runTest {
        vm.onIntent(TextureIntent.Open(target))
        assertEquals(2, vm.state.value.library.size)
        assertNull(vm.state.value.activeLayer)

        vm.onIntent(TextureIntent.Choose(TextureChoice.NewLayer))
        vm.onIntent(TextureIntent.PickLibrary(vm.state.value.library.first()))
        assertNull(vm.state.value.choosing)
        assertEquals("Knurl", vm.state.value.activeLayer?.name)
        assertEquals(TextureEvent.Changed, vm.events.first())

        // A picture of one's own replaces the texture, the layer stays.
        vm.onIntent(TextureIntent.Choose(TextureChoice.Replace(0)))
        vm.onIntent(TextureIntent.PickedFile("content://photo"))
        assertEquals("photo", vm.state.value.activeLayer?.name)
        assertEquals(1, vm.state.value.stack.layers.size)
    }

    @Test
    fun `slider changes show at once and reach the engine once they rest`() {
        vm.onIntent(TextureIntent.Open(target))
        vm.onIntent(TextureIntent.PickLibrary(LibraryTexture("Knurl", "/res/Knurl.png")))
        val settings = vm.state.value.activeLayer!!.settings
        vm.onIntent(TextureIntent.Edit(settings.copy(depthMm = 0.8f)))
        vm.onIntent(TextureIntent.Edit(settings.copy(depthMm = 1.2f)))
        assertEquals(1.2f, vm.state.value.activeLayer!!.settings.depthMm)
        assertTrue(textures.writes.isEmpty())
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(1.2f), textures.writes.map { it.depthMm })
    }

    @Test
    fun `bake needs paint, then makes the relief the mesh`() = runTest {
        vm.onIntent(TextureIntent.Open(target))
        vm.onIntent(TextureIntent.PickLibrary(LibraryTexture("Knurl", "/res/Knurl.png")))
        vm.events.first()

        vm.onIntent(TextureIntent.Bake)
        assertNotNull(vm.state.value.error)
        assertEquals(0, textures.baked)

        vm.onIntent(TextureIntent.PaintAll(erase = false))
        assertEquals(TextureEvent.PaintChanged, vm.events.first())
        assertTrue(vm.state.value.stack.anyPainted)

        vm.onIntent(TextureIntent.Bake)
        assertEquals(1, textures.baked)
        val event = vm.events.first()
        assertTrue("$event", event is TextureEvent.Baked)
        assertEquals(40_000L, (event as TextureEvent.Baked).objects.single().triangles)
        assertEquals(40_000L, vm.state.value.lastBake?.trianglesAfter)
        assertNull(vm.state.value.bakingPercent)
        // The layer stays; its paint is used up.
        assertEquals(1, vm.state.value.stack.layers.size)
        assertTrue(!vm.state.value.stack.anyPainted)
    }

    @Test
    fun `fingers shift, size and turn the texture`() {
        vm.onIntent(TextureIntent.Open(target))
        vm.onIntent(TextureIntent.PickLibrary(LibraryTexture("Knurl", "/res/Knurl.png")))
        vm.onIntent(TextureIntent.SetPlacing(true))
        assertTrue(vm.state.value.placing)
        val start = vm.state.value.activeLayer!!.settings
        vm.onIntent(TextureIntent.Place(scale = 2f))
        vm.onIntent(TextureIntent.Place(turnDegrees = 30f))
        // A drag of 400 px is one tile: back where it started.
        vm.onIntent(TextureIntent.Place(dxPx = 100f))
        val placed = vm.state.value.activeLayer!!.settings
        assertEquals(start.tileMm * 2, placed.tileMm, 1e-4f)
        assertEquals(-30f, placed.rotationDegrees, 1e-4f)
        assertEquals(-start.tileMm * 2 / 4, placed.offsetXMm, 1e-3f)
        vm.onIntent(TextureIntent.Place(dxPx = 300f))
        assertEquals(0f, vm.state.value.activeLayer!!.settings.offsetXMm, 1e-3f)
        val revision = vm.state.value.revision
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, textures.writes.size)
        assertTrue(vm.state.value.revision > revision)
    }

    @Test
    fun `removing the active layer moves to the next one`() {
        vm.onIntent(TextureIntent.Open(target))
        vm.onIntent(TextureIntent.PickLibrary(LibraryTexture("Knurl", "/res/Knurl.png")))
        vm.onIntent(TextureIntent.Choose(TextureChoice.NewLayer))
        vm.onIntent(TextureIntent.PickLibrary(LibraryTexture("Noise", "/res/Noise.png")))
        assertEquals(1, vm.state.value.active)
        vm.onIntent(TextureIntent.Remove(1))
        assertEquals(0, vm.state.value.active)
    }
}
