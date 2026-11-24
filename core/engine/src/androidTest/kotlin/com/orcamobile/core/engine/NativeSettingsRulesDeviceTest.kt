package com.orcamobile.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.engine.resources.EngineResources
import com.orcamobile.core.model.RulesScope
import com.orcamobile.core.model.SettingsReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The desktop's rules between settings (ConfigManipulation and the tabs), run by the engine. */
@RunWith(AndroidJUnit4::class)
class NativeSettingsRulesDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        assertNull("The engine library failed to load", NativeEngine.loadError)
        EngineEnvironment(context, EngineResources(context)).ensureInitialized()
    }

    private fun review(settings: Map<String, String>, scope: RulesScope = RulesScope.GLOBAL): SettingsReview {
        val keys = settings.keys.toTypedArray()
        val values = keys.map { settings.getValue(it) }.toTypedArray()
        return SettingsJson.review(NativeEngine.nativeReviewSettings("", keys, values, scope.nativeValue, 0)).getOrThrow()
    }

    @Test
    fun supportSettingsFollowEnableSupport() {
        val off = review(mapOf("enable_support" to "0"))
        val type = off.stateOf("support_type")
        assertFalse(type.enabled)
        assertTrue(type.reason, type.reason.contains("support", ignoreCase = true))

        val on = review(mapOf("enable_support" to "1", "support_type" to "normal(auto)"))
        assertTrue(on.stateOf("support_type").enabled)
        assertTrue(on.stateOf("support_threshold_angle").enabled)
        // Tree-only settings are hidden for normal supports.
        assertFalse(on.stateOf("tree_support_branch_angle").visible)
    }

    @Test
    fun ironingSettingsShowOnlyWithIroning() {
        assertFalse(review(mapOf("ironing_type" to "no ironing")).stateOf("ironing_flow").visible)
        assertTrue(review(mapOf("ironing_type" to "top")).stateOf("ironing_flow").visible)
    }

    @Test
    fun aZeroLayerHeightIsResetWithANotice() {
        val reviewed = review(mapOf("layer_height" to "0"))
        assertEquals("0.2", reviewed.changes["layer_height"])
        assertTrue(reviewed.notices.any { it.contains("Layer height") })
    }

    @Test
    fun spiralVaseConflictsOfferBothWaysOut() {
        val reviewed = review(mapOf("spiral_mode" to "1", "wall_loops" to "3"))
        val check = reviewed.checks.single { it.id == "spiral_mode" }
        assertEquals(2, check.fixes.size)
        assertEquals("1", check.fixes[0].changes["wall_loops"])
        assertEquals("0", check.fixes[1].changes["spiral_mode"])

        // Taking the first way out leaves nothing to ask.
        val fixed = review(mapOf("spiral_mode" to "1") + check.fixes[0].changes)
        assertTrue(fixed.checks.none { it.id == "spiral_mode" })
    }

    @Test
    fun objectSettingsAreReviewedWithoutPrinterRules() {
        val reviewed = review(mapOf("enable_support" to "0"), RulesScope.OBJECT)
        assertFalse(reviewed.stateOf("support_type").enabled)
        // Printer rules belong to the presets: none for an object.
        assertTrue(reviewed.options.keys.none { it.startsWith("machine_max_jerk") })
    }
}
