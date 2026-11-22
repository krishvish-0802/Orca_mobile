package com.orcamobile.core.model

/** A newer build of the app, as the latest-build release publishes it. */
data class AppUpdate(
    /** The build number (CI run): larger is newer. */
    val versionCode: Long,
    val commit: String,
    /** The latest changes, newest first. */
    val changes: List<String>,
    /** Where this phone's APK downloads from. */
    val apkUrl: String,
)

/** What came of handing a downloaded update to Android's installer. */
sealed interface InstallStart {
    /** Android's install screen is open. */
    data object Started : InstallStart

    /** Android first needs the user to let this app install apps; its settings screen is open. */
    data object NeedsPermission : InstallStart
}
