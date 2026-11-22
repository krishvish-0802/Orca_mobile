package com.orcamobile.core.update

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseManifestTest {

    private val manifest = ReleaseManifest.parse(
        """{"versionCode": 152, "commit": "abc1234", "builtAt": "2026-11-22T17:00:00Z",
           "changes": ["Split to objects and Split to parts", "Painting"],
           "apks": {"arm64-v8a": "orca-mobile-arm64-v8a.apk", "x86_64": "orca-mobile-x86_64.apk"}}""",
    )
    private val url = "https://example.com/latest-build/"

    @Test
    fun `offers a newer build for the phone's first ABI the release has`() {
        val update = manifest.updateFor(installed = 150, abis = listOf("arm64-v8a", "armeabi-v7a"), releaseUrl = url)
        assertEquals(152, update?.versionCode)
        assertEquals("https://example.com/latest-build/orca-mobile-arm64-v8a.apk", update?.apkUrl)
        assertEquals("Split to objects and Split to parts", update?.changes?.first())
        assertEquals("$url" + "orca-mobile-x86_64.apk", manifest.updateFor(1, listOf("x86_64"), url)?.apkUrl)
    }

    @Test
    fun `nothing when up to date or when no APK fits`() {
        assertNull(manifest.updateFor(installed = 152, abis = listOf("arm64-v8a"), releaseUrl = url))
        assertNull(manifest.updateFor(installed = 1, abis = listOf("armeabi-v7a"), releaseUrl = url))
    }
}
