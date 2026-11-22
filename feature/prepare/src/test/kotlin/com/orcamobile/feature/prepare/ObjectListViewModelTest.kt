package com.orcamobile.feature.prepare

import com.orcamobile.core.domain.engine.ConfigOptionCatalog
import com.orcamobile.core.domain.model.ModelImportRepository
import com.orcamobile.core.domain.project.ObjectEditor
import com.orcamobile.core.domain.project.PlateConfigResolver
import com.orcamobile.core.domain.settings.PrintSettingsRepository
import com.orcamobile.core.model.Axes
import com.orcamobile.core.model.ConfigOption
import com.orcamobile.core.model.InstanceTransform
import com.orcamobile.core.model.LayerRange
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.ObjectDetails
import com.orcamobile.core.model.ObjectEdit
import com.orcamobile.core.model.ObjectUpdate
import com.orcamobile.core.model.PresetOptionKeys
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.PrimitiveShape
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.SettingScope
import com.orcamobile.core.model.SettingsEdits
import com.orcamobile.core.model.TransformEdit
import com.orcamobile.core.model.VolumeInfo
import com.orcamobile.core.model.VolumeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ObjectListViewModelTest {

    private val project = ProjectId("p1")
    private val presets = PresetSelection("Elegoo", "Centauri Carbon", "0.20mm Standard", listOf("PLA"))
    private val placement = InstanceTransform(
        position = Axes(0.0, 0.0, 0.0),
        rotationDegrees = Axes(0.0, 0.0, 0.0),
        scale = Axes(1.0, 1.0, 1.0),
        size = Axes(20.0, 20.0, 20.0),
        mirrored = emptySet(),
    )

    private inner class FakeObjects : ObjectEditor {
        val edits = mutableListOf<ObjectEdit>()
        var details = ObjectDetails(
            "cube",
            emptyMap(),
            listOf(VolumeInfo("cube", VolumeKind.PART, 12, emptyMap(), placement)),
            emptyList(),
        )

        private fun update() = ObjectUpdate(details, listOf(ModelObjectInfo("cube", 1, 12, listOf(20.0, 20.0, 20.0))))

        override suspend fun details(project: ProjectId, objectIndex: Int) = Result.success(update())

        override suspend fun edit(project: ProjectId, objectIndex: Int, edit: ObjectEdit): Result<ObjectUpdate> {
            edits += edit
            when (edit) {
                is ObjectEdit.AddPrimitive -> details = details.copy(
                    volumes = details.volumes + VolumeInfo("Generic-Cube", edit.kind, 12, emptyMap(), placement),
                )
                is ObjectEdit.AddRange -> details = details.copy(
                    layerRanges = details.layerRanges + LayerRange(edit.minZ, edit.maxZ, mapOf("layer_height" to edit.layerHeight)),
                )
                is ObjectEdit.RemoveVolume -> details = details.copy(volumes = details.volumes.filterIndexed { i, _ -> i != edit.volume })
                else -> Unit
            }
            return Result.success(update())
        }

        override suspend fun settingKeys(scope: SettingScope) = Result.success(listOf("layer_height"))
    }

    private class FakeImports : ModelImportRepository {
        override suspend fun import(uri: String) = Result.success("/work/${uri.substringAfterLast('/')}")
    }

    private class FakeCatalog : ConfigOptionCatalog {
        override suspend fun describe(keys: List<String>) = Result.success(emptyList<ConfigOption>())
        override suspend fun values(selection: PresetSelection?, keys: List<String>) =
            Result.success(mapOf("layer_height" to "0.16"))
        override suspend fun presetOptionKeys() = Result.success(PresetOptionKeys(emptyList(), emptyList(), emptyList()))
    }

    private class NoEdits : PrintSettingsRepository {
        override val edits: Flow<SettingsEdits> = flowOf(SettingsEdits.NONE)
        override suspend fun editsFor(selection: PresetSelection?) = emptyMap<String, String>()
        override suspend fun set(selection: PresetSelection?, key: String, value: String) = Unit
        override suspend fun reset(selection: PresetSelection?, key: String) = Unit
        override suspend fun resetAll() = Unit
    }

    private val objects = FakeObjects()
    private val vm = ObjectListViewModel(objects, FakeImports(), FakeCatalog(), PlateConfigResolver(NoEdits()))
    private fun context(editable: Boolean = true) = ObjectListContext(project, 0, presets, bedSizeMm = 256.0, editable = editable)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `shows the selected object and nothing without one`() {
        vm.onIntent(ObjectListIntent.Show(context()))
        assertEquals("cube", vm.state.value.details?.name)
        vm.onIntent(ObjectListIntent.Show(null))
        assertNull(vm.state.value.details)
    }

    @Test
    fun `a new shape is a tenth of the bed and redraws the plate`() = runTest {
        vm.onIntent(ObjectListIntent.Show(context()))
        vm.onIntent(ObjectListIntent.AddShape(VolumeKind.MODIFIER, PrimitiveShape.CUBE))
        assertEquals(ObjectEdit.AddPrimitive(VolumeKind.MODIFIER, PrimitiveShape.CUBE, 25.6), objects.edits.single())
        assertIs<ObjectListEvent.PlateChanged>(vm.events.first())
        assertEquals(2, vm.state.value.details?.volumes?.size)
    }

    @Test
    fun `a file becomes a volume once imported`() {
        vm.onIntent(ObjectListIntent.Show(context()))
        vm.onIntent(ObjectListIntent.AddFile(VolumeKind.NEGATIVE_VOLUME, "content://docs/hole.stl"))
        assertEquals(ObjectEdit.AddFile(VolumeKind.NEGATIVE_VOLUME, "/work/hole.stl"), objects.edits.single())
    }

    @Test
    fun `height ranges stack up with the slice's layer height`() = runTest {
        vm.onIntent(ObjectListIntent.Show(context()))
        vm.onIntent(ObjectListIntent.AddRange)
        vm.onIntent(ObjectListIntent.AddRange)
        assertEquals(
            listOf<ObjectEdit>(ObjectEdit.AddRange(0.0, 2.0, "0.16"), ObjectEdit.AddRange(2.0, 4.0, "0.16")),
            objects.edits,
        )
        assertEquals(ObjectListEvent.SettingsChanged, vm.events.first())
    }

    @Test
    fun `placing a volume keeps what was not edited`() {
        vm.onIntent(ObjectListIntent.Show(context()))
        vm.onIntent(ObjectListIntent.SelectVolume(0))
        vm.onIntent(ObjectListIntent.EditVolume(TransformEdit.Position(5.0, 6.0, 7.0)))
        vm.onIntent(ObjectListIntent.EditVolume(TransformEdit.Mirror(com.orcamobile.core.model.Axis.X)))
        assertEquals(
            listOf<ObjectEdit>(ObjectEdit.VolumeTransform(0, Axes(5.0, 6.0, 7.0), placement.rotationDegrees, placement.scale)),
            objects.edits,
        )
    }

    @Test
    fun `nothing changes while the plate is busy`() {
        vm.onIntent(ObjectListIntent.Show(context(editable = false)))
        vm.onIntent(ObjectListIntent.AddShape(VolumeKind.PART, PrimitiveShape.SPHERE))
        assertTrue(objects.edits.isEmpty())
    }

    @Test
    fun `removing the selected volume clears the selection`() {
        objects.details = objects.details.copy(volumes = objects.details.volumes + VolumeInfo("mod", VolumeKind.MODIFIER, 12, emptyMap(), placement))
        vm.onIntent(ObjectListIntent.Show(context()))
        vm.onIntent(ObjectListIntent.SelectVolume(1))
        vm.onIntent(ObjectListIntent.RemoveVolume(1))
        assertNull(vm.state.value.selectedVolume)
        assertEquals(1, vm.state.value.details?.volumes?.size)
    }

    @Test
    fun `a volume picked on another object is shown once that object is`() {
        vm.onIntent(ObjectListIntent.Show(context()))
        vm.onIntent(ObjectListIntent.SelectVolume(0, objectIndex = 3))
        assertNull(vm.state.value.selectedVolume)
        vm.onIntent(ObjectListIntent.Show(context().copy(objectIndex = 3)))
        assertEquals(0, vm.state.value.selectedVolume)
        vm.onIntent(ObjectListIntent.Show(context().copy(objectIndex = 1)))
        assertNull(vm.state.value.selectedVolume)
    }
}
