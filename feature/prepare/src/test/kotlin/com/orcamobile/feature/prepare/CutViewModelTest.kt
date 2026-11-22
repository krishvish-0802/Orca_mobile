package com.orcamobile.feature.prepare

import com.orcamobile.core.domain.project.MeshEditor
import com.orcamobile.core.model.ConnectorProblem
import com.orcamobile.core.model.CutCheck
import com.orcamobile.core.model.CutConnector
import com.orcamobile.core.model.CutConnectorType
import com.orcamobile.core.model.CutPlanePoint
import com.orcamobile.core.model.CutRequest
import com.orcamobile.core.model.InstanceRef
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
class CutViewModelTest {

    private val project = ProjectId("p1")
    private val cube = InstanceRef(0, 0)

    /** A 20 mm cube; connectors further than 8 mm from the middle are off its face. */
    private class FakeMeshes : MeshEditor {
        val cuts = mutableListOf<CutRequest>()
        var failCut = false

        override suspend fun checkCut(project: ProjectId, request: CutRequest) = Result.success(check(request))

        override suspend fun cut(project: ProjectId, request: CutRequest): Result<PlateUpdate> {
            if (failCut) return Result.failure(IllegalStateException("The cut left nothing to keep"))
            cuts += request
            val pieces = if (request.connectors.any { it.type == CutConnectorType.DOWEL }) 3 else 2
            return Result.success(PlateUpdate(List(pieces) { ModelObjectInfo("piece $it", 1, 12, listOf(20.0, 20.0, 10.0)) }, placed = pieces))
        }

        fun check(request: CutRequest): CutCheck {
            val problems = request.connectors.map { if (kotlin.math.hypot(it.u, it.v) > 8) ConnectorProblem.OUTSIDE_SECTION else ConnectorProblem.NONE }
            val ok = problems.all { it == ConnectorProblem.NONE } && request.plane.heightMm in 0.0..20.0
            return CutCheck(
                boxMin = listOf(0.0, 0.0, 0.0),
                boxMax = listOf(20.0, 20.0, 20.0),
                center = listOf(10.0, 10.0, request.plane.heightMm),
                normal = listOf(0.0, 0.0, 1.0),
                problems = problems,
                canCut = ok,
                reason = if (ok) "" else "A connector does not fit where it is",
                dovetailDepthMm = 1.0,
            )
        }
    }

    private val meshes = FakeMeshes()
    private val viewModel by lazy { CutViewModel(meshes) }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun started(): CutViewModel = viewModel.apply {
        onIntent(CutIntent.Start(project, cube))
        // The plate reports its checks as it shows the cut.
        onIntent(CutIntent.Checked(meshes.check(state.value.request!!)))
    }

    @Test
    fun `the plane starts through the middle and stays on the object`() {
        val vm = started()
        assertEquals(10.0, vm.state.value.request!!.plane.heightMm)
        vm.onIntent(CutIntent.MoveHeight(25.0))
        assertEquals(20.0, vm.state.value.request!!.plane.heightMm)
        vm.onIntent(CutIntent.SetHeight(-5.0))
        assertEquals(0.0, vm.state.value.request!!.plane.heightMm)
        vm.onIntent(CutIntent.SetTilt(30.0, 0.0))
        vm.onIntent(CutIntent.ResetPlane)
        assertEquals(10.0, vm.state.value.request!!.plane.heightMm)
        assertEquals(0.0, vm.state.value.request!!.plane.tiltXDegrees)
    }

    @Test
    fun `taps on the face add connectors and pick them, taps off it do not`() {
        val vm = started()
        vm.onIntent(CutIntent.TapPlane(CutPlanePoint(1.0, 1.0, insideSection = true)))
        assertTrue(vm.state.value.request!!.connectors.isEmpty(), "taps add nothing until adding is on")

        vm.onIntent(CutIntent.SetAddingConnectors(true))
        vm.onIntent(CutIntent.TapPlane(CutPlanePoint(1.0, 1.0, insideSection = true)))
        vm.onIntent(CutIntent.TapPlane(CutPlanePoint(30.0, 0.0, insideSection = false)))
        assertEquals(1, vm.state.value.request!!.connectors.size)
        assertNull(vm.state.value.selectedConnector)

        // A tap on the connector picks it; new settings change it and the next one.
        vm.onIntent(CutIntent.TapPlane(CutPlanePoint(2.0, 1.0, insideSection = true)))
        assertEquals(0, vm.state.value.selectedConnector)
        vm.onIntent(CutIntent.SetConnector(CutConnector(type = CutConnectorType.DOWEL, sizeMm = 3.0)))
        val connector = vm.state.value.request!!.connectors.single()
        assertEquals(CutConnectorType.DOWEL, connector.type)
        assertEquals(1.0, connector.u)
        assertEquals(CutConnectorType.DOWEL, vm.state.value.connector.type)

        vm.onIntent(CutIntent.RemoveSelectedConnector)
        assertTrue(vm.state.value.request!!.connectors.isEmpty())
    }

    @Test
    fun `perform cuts and closes the tool`() = runTest {
        val vm = started()
        vm.onIntent(CutIntent.SetAddingConnectors(true))
        vm.onIntent(CutIntent.SetConnector(CutConnector(type = CutConnectorType.DOWEL)))
        vm.onIntent(CutIntent.TapPlane(CutPlanePoint(0.0, 0.0, insideSection = true)))
        vm.onIntent(CutIntent.Checked(meshes.check(vm.state.value.request!!)))
        vm.onIntent(CutIntent.Perform)

        val event = assertIs<CutEvent.Cut>(vm.events.first())
        assertEquals(3, event.pieces)
        assertEquals(1, meshes.cuts.single().connectors.size)
        assertFalse(vm.state.value.active)
    }

    @Test
    fun `a cut that cannot be made is not tried, and a failure stays open with the reason`() {
        val vm = started()
        vm.onIntent(CutIntent.SetAddingConnectors(true))
        vm.onIntent(CutIntent.TapPlane(CutPlanePoint(9.0, 0.0, insideSection = true)))
        vm.onIntent(CutIntent.Checked(meshes.check(vm.state.value.request!!)))
        assertFalse(vm.state.value.canCut)
        vm.onIntent(CutIntent.Perform)
        assertTrue(meshes.cuts.isEmpty())

        vm.onIntent(CutIntent.RemoveAllConnectors)
        vm.onIntent(CutIntent.Checked(meshes.check(vm.state.value.request!!)))
        meshes.failCut = true
        vm.onIntent(CutIntent.Perform)
        assertTrue(vm.state.value.active)
        assertEquals("The cut left nothing to keep", vm.state.value.error)
    }
}
