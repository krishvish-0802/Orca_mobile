package com.orcamobile.feature.prepare

import com.orcamobile.core.domain.project.MeshEditor
import com.orcamobile.core.model.AssemblyOptions
import com.orcamobile.core.model.AssemblyStep
import com.orcamobile.core.model.CutCheck
import com.orcamobile.core.model.CutRequest
import com.orcamobile.core.model.FeatureFilter
import com.orcamobile.core.model.FeatureKind
import com.orcamobile.core.model.FeaturePick
import com.orcamobile.core.model.MeasureOutcome
import com.orcamobile.core.model.MeasureRequest
import com.orcamobile.core.model.MeasuredFeature
import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.PlateUpdate
import com.orcamobile.core.model.ProjectId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MeasureViewModelTest {

    private val project = ProjectId("p1")

    private class FakeMeshes : MeshEditor {
        val steps = mutableListOf<Triple<AssemblyStep, FeatureFilter, Double>>()

        override suspend fun checkCut(project: ProjectId, request: CutRequest): Result<CutCheck> = error("not used")

        override suspend fun cut(project: ProjectId, request: CutRequest): Result<PlateUpdate> = error("not used")

        override suspend fun measure(project: ProjectId, request: MeasureRequest): Result<MeasureOutcome> = error("not used")

        override suspend fun assemble(
            project: ProjectId,
            first: FeaturePick,
            second: FeaturePick,
            request: MeasureRequest,
            step: AssemblyStep,
            value: Double,
            xyz: List<Double>,
        ): Result<PlateUpdate> {
            steps += Triple(step, request.filter, value)
            return Result.success(PlateUpdate(listOf(ModelObjectInfo("a", 1, 12, listOf(10.0, 10.0, 10.0)))))
        }
    }

    private val meshes = FakeMeshes()
    private val viewModel by lazy { MeasureViewModel(meshes) }

    private fun pick(objectIndex: Int) = FeaturePick(objectIndex, 0, 0, 3, listOf(0.0, 0.0, 0.0))

    private fun face() = MeasuredFeature(FeatureKind.PLANE, listOf(1.0, 0.0, 0.0), listOf(10.0, 5.0, 5.0), 0.0)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `two taps measure, a third starts over`() {
        val vm = viewModel
        vm.onIntent(MeasureIntent.Open(project))
        vm.onIntent(MeasureIntent.Pick(pick(0)))
        vm.onIntent(MeasureIntent.Pick(pick(1)))
        assertEquals(2, vm.state.value.request!!.picks.size)
        vm.onIntent(MeasureIntent.Pick(pick(2)))
        assertEquals(listOf(pick(2)), vm.state.value.picks)
    }

    @Test
    fun `a tap that finds no feature is dropped`() {
        val vm = viewModel
        vm.onIntent(MeasureIntent.Open(project))
        vm.onIntent(MeasureIntent.SetMode(MeasureMode.ASSEMBLE_FACES))
        assertEquals(FeatureFilter.PLANES, vm.state.value.filter)
        vm.onIntent(MeasureIntent.Pick(pick(0)))
        vm.onIntent(MeasureIntent.Measured(MeasureOutcome(listOf(face()), null, AssemblyOptions())))
        vm.onIntent(MeasureIntent.Pick(pick(1)))
        vm.onIntent(MeasureIntent.Measured(MeasureOutcome(listOf(face()), null, AssemblyOptions())))
        assertEquals(listOf(pick(0)), vm.state.value.picks)
        assertNull(vm.state.value.outcome)
    }

    @Test
    fun `assembly steps run with the mode's filter and redraw the plate`() = runTest {
        val vm = viewModel
        vm.onIntent(MeasureIntent.Open(project))
        vm.onIntent(MeasureIntent.SetMode(MeasureMode.ASSEMBLE_FACES))
        vm.onIntent(MeasureIntent.Pick(pick(0)))
        vm.onIntent(MeasureIntent.Pick(pick(1)))
        vm.onIntent(MeasureIntent.Assemble(AssemblyStep.ROTATE_AROUND, value = 90.0))
        assertEquals(Triple(AssemblyStep.ROTATE_AROUND, FeatureFilter.PLANES, 90.0), meshes.steps.single())
        assertIs<MeasureEvent.PlateChanged>(vm.events.first())
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `closing forgets the picks and keeps the mode`() {
        val vm = viewModel
        vm.onIntent(MeasureIntent.Open(project))
        vm.onIntent(MeasureIntent.SetMode(MeasureMode.ASSEMBLE_POINTS))
        vm.onIntent(MeasureIntent.Pick(pick(0)))
        vm.onIntent(MeasureIntent.Close)
        assertNull(vm.state.value.request)
        assertTrue(vm.state.value.picks.isEmpty())
        assertEquals(MeasureMode.ASSEMBLE_POINTS, vm.state.value.mode)
    }
}
