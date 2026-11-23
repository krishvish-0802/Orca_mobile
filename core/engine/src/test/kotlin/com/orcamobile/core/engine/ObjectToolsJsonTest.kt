package com.orcamobile.core.engine

import com.orcamobile.core.model.BrimEar
import com.orcamobile.core.model.LayerBrush
import com.orcamobile.core.model.LayerProfileAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObjectToolsJsonTest {

    @Test
    fun `layer actions name what the bridge expects`() {
        val brush = Json.parseToJsonElement(ObjectToolsJson.encode(LayerProfileAction.Brush(10.0, 0.02, 2.0, LayerBrush.SMOOTH))).jsonObject
        assertEquals("brush", brush["action"]!!.jsonPrimitive.content)
        assertEquals(3, brush["brush"]!!.jsonPrimitive.content.toInt())
        val profile = ObjectToolsJson.layerProfile(
            """{"profile":[0,0.2,20,0.2],"layers":[0,0.2,0.2,0.4],"layerHeight":0.2,"minLayerHeight":0.07,""" +
                """"maxLayerHeight":0.32,"objectHeight":20,"custom":false}""",
        ).getOrThrow()
        assertEquals(2, profile.layerCount)
        assertTrue(ObjectToolsJson.layerProfile("""{"error":"The object is too thin"}""").isFailure)
    }

    @Test
    fun `ears and taps round trip`() {
        val ears = listOf(BrimEar(1.0, 2.0, 3.0))
        assertEquals(ears, ObjectToolsJson.ears("""{"ears":${ObjectToolsJson.encodeEars(ears)}}""").getOrThrow())
        assertNull(ObjectToolsJson.earPoint("{}"))
        val missed = ObjectToolsJson.earPoint("""{"bedX":5,"bedY":6}""")!!
        assertNull(missed.x)
        assertEquals(5.0, missed.bedX)
    }
}
