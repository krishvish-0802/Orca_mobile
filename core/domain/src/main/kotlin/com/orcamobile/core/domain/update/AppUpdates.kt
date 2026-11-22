package com.orcamobile.core.domain.update

import com.orcamobile.core.model.AppUpdate
import com.orcamobile.core.model.InstallStart

/** Newer builds of the app published on GitHub, and installing them. */
interface AppUpdates {
    /** The latest build when it is newer than this one; null when up to date. */
    suspend fun check(): Result<AppUpdate?>

    /** Downloads the update's APK; returns its local path. [onProgress] gets bytes so far and the total (-1 if unknown). */
    suspend fun download(update: AppUpdate, onProgress: (read: Long, total: Long) -> Unit): Result<String>

    /** Opens Android's installer on a downloaded APK. */
    fun install(apkPath: String): Result<InstallStart>
}
