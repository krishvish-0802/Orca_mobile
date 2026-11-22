package com.orcamobile.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The version.json the CI publishes with each build (.github/workflows/native.yml). */
@Serializable
internal data class ReleaseManifest(
    val versionCode: Long,
    val commit: String = "",
    val changes: List<String> = emptyList(),
    /** APK file names by ABI. */
    val apks: Map<String, String> = emptyMap(),
) {
    /** The APK for the first of [abis] (the device's, preferred first) that the release has. */
    fun apkFor(abis: List<String>): String? = abis.firstNotNullOfOrNull { apks[it] }

    /** The update this release offers a phone with [installed] and [abis]: null when not newer or no APK fits. */
    fun updateFor(installed: Long, abis: List<String>, releaseUrl: String): com.orcamobile.core.model.AppUpdate? {
        val apk = apkFor(abis) ?: return null
        if (versionCode <= installed) return null
        return com.orcamobile.core.model.AppUpdate(versionCode, commit, changes, releaseUrl + apk)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ReleaseManifest = json.decodeFromString(serializer(), text)
    }
}
