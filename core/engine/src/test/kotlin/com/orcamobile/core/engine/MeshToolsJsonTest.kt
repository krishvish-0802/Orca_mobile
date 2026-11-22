package com.orcamobile.core.engine

import com.orcamobile.core.model.BooleanOperation
import com.orcamobile.core.model.ConnectorProblem
import com.orcamobile.core.model.CutConnector
import com.orcamobile.core.model.CutConnectorShape
import com.orcamobile.core.model.CutConnectorType
import com.orcamobile.core.model.CutMode
import com.orcamobile.core.model.CutPlane
import com.orcamobile.core.model.CutRequest
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.ObjectEdit
import com.orcamobile.core.model.SimplifyDetail
import com.orcamobile.core.model.SimplifySettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshToolsJsonTest {

    @Test
    fun `a cut request carries the plane, options and connectors as the bridge reads them`() {
        val request = CutRequest(
            InstanceRef(2, 1),
            CutPlane(12.5, tiltXDegrees = 30.0),
            mode = CutMode.PLANAR,
            connectors = listOf(CutConnector(u = 1.0, v = -2.0, type = CutConnectorType.SNAP, shape = CutConnectorShape.HEXAGON)),
        )
        val j = Json.parseToJsonElement(MeshToolsJson.encode(request)).jsonObject
        assertEquals(2, j["object"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, j["instance"]!!.jsonPrimitive.content.toInt())
        assertEquals(12.5, j["plane"]!!.jsonObject["height"]!!.jsonPrimitive.content.toDouble())
        val connector = j["connectors"]!!.jsonArray.single().jsonObject
        assertEquals(2, connector["type"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, connector["shape"]!!.jsonPrimitive.content.toInt())
        assertEquals(-2.0, connector["v"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `checks and plane points read back, errors fail`() {
        val check = MeshToolsJson.cutCheck(
            """{"boxMin":[0,0,0],"boxMax":[20,20,30],"center":[10,10,15],"normal":[0,0,1],"axisX":[1,0,0],""" +
                """"axisY":[0,1,0],"problems":[0,2],"canCut":false,"reason":"A connector does not fit where it is","dovetailDepth":1.2}""",
        ).getOrThrow()
        assertEquals(listOf(ConnectorProblem.NONE, ConnectorProblem.OVERLAPPING), check.problems)
        assertEquals(0.0..30.0, check.heightRange)
        assertTrue(MeshToolsJson.cutCheck("""{"error":"No such instance on the plate"}""").isFailure)
        assertEquals(true, MeshToolsJson.planePoint("""{"u":1.5,"v":-1,"inside":true}""")?.insideSection)
        assertNull(MeshToolsJson.planePoint("{}"))
    }

    @Test
    fun `simplify and boolean edits name their arguments`() {
        val simplify = Json.parseToJsonElement(
            EngineJson.encodeObjectEdit(ObjectEdit.Simplify(null, SimplifySettings(detail = SimplifyDetail.LOW))),
        ).jsonObject["simplify"]!!.jsonObject
        assertEquals("null", simplify["volume"].toString())
        assertEquals(3, simplify["detail"]!!.jsonPrimitive.content.toInt())
        val boolean = Json.parseToJsonElement(
            EngineJson.encodeObjectEdit(ObjectEdit.MeshBoolean(0, 2, BooleanOperation.INTERSECTION, deleteTool = true)),
        ).jsonObject["boolean"]!!.jsonObject
        assertEquals(2, boolean["operation"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", boolean["deleteTool"]!!.jsonPrimitive.content)
        assertEquals(1L, MeshToolsJson.simplifyCounts("""{"before":2,"after":1}""").getOrThrow().after)
    }
}
