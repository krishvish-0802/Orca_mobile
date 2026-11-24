package com.orcamobile.feature.prepare

import com.orcamobile.core.model.ModelObjectInfo
import com.orcamobile.core.model.SliceMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class SliceMessagesTest {

    private val objects = listOf(ModelObjectInfo("cube", 1, 12, listOf(20.0, 20.0, 20.0)), ModelObjectInfo("tower", 1, 12, listOf(5.0, 5.0, 50.0)))

    @Test
    fun `an object nothing holds up gets supports or another side down`() {
        val message = SliceMessage(SliceMessage.Level.ERROR, "One object has an empty first layer...", "tower", code = "empty_first_layer")
        assertEquals(listOf(SliceFix.TurnOnSupports, SliceFix.LayFlat(1)), fixesFor(message, objects))
    }

    @Test
    fun `a message about a setting opens it, and one about an object shows it`() {
        val message = SliceMessage(SliceMessage.Level.WARNING, "Line width too large", "cube", setting = "outer_wall_line_width")
        assertEquals(listOf(SliceFix.OpenSetting("outer_wall_line_width"), SliceFix.Show(0)), fixesFor(message, objects))
    }

    @Test
    fun `a message about nothing in particular offers nothing`() {
        assertEquals(emptyList<SliceFix>(), fixesFor(SliceMessage(SliceMessage.Level.WARNING, "Some warning"), objects))
    }
}
