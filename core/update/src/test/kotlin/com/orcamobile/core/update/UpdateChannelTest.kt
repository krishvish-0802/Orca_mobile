package com.orcamobile.core.update

import org.junit.Test
import kotlin.test.assertEquals

class UpdateChannelTest {
    private val base = "https://github.com/krishvish-0802/Orca_mobile/releases/download/"

    @Test
    fun `main's builds follow main's release only`() {
        assertEquals(listOf("${base}latest-build/"), GitHubAppUpdates.releaseUrlsFor("latest-build"))
    }

    @Test
    fun `a milestone build follows its own release, then main's`() {
        assertEquals(
            listOf("${base}milestone-2-build/", "${base}latest-build/"),
            GitHubAppUpdates.releaseUrlsFor("milestone-2-build"),
        )
    }
}
