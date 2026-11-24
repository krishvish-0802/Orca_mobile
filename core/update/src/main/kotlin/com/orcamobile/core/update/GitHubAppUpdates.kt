package com.orcamobile.core.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.update.AppUpdates
import com.orcamobile.core.model.AppUpdate
import com.orcamobile.core.model.InstallStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Updates from one of the repository's rolling releases: its version.json
 * names the build (the CI run number, which is the APK's versionCode) and the
 * APK for each ABI. A milestone branch's build follows that branch's release
 * first; once the release is gone (the milestone was merged), main's.
 */
internal class GitHubAppUpdates(
    private val context: Context,
    private val http: OkHttpClient,
    private val dispatchers: CoroutineDispatchers,
    /** The releases' download bases, each ending in a slash: the first that exists is used. */
    private val releaseUrls: List<String> = releaseUrlsFor(BuildConfig.UPDATE_TAG),
    private val installedVersion: () -> Long = { installedVersionCode(context) },
    private val abis: List<String> = Build.SUPPORTED_ABIS.toList(),
) : AppUpdates {

    override suspend fun check(): Result<AppUpdate?> = withContext(dispatchers.io) {
        runCatching {
            for ((i, releaseUrl) in releaseUrls.withIndex()) {
                val request = Request.Builder().url(releaseUrl + MANIFEST).header("Cache-Control", "no-cache").build()
                val text = http.newCall(request).execute().use { response ->
                    // No such release: try the next channel.
                    if (response.code == HTTP_NOT_FOUND && i < releaseUrls.lastIndex) return@use null
                    if (!response.isSuccessful) throw IOException("The update check got HTTP ${response.code}")
                    response.body?.string().orEmpty()
                } ?: continue
                return@runCatching ReleaseManifest.parse(text).updateFor(installedVersion(), abis, releaseUrl)
            }
            null
        }
    }

    override suspend fun download(update: AppUpdate, onProgress: (read: Long, total: Long) -> Unit): Result<String> =
        withContext(dispatchers.io) {
            runCatching {
                val dir = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }   // only the newest is kept
                val target = File(dir, "orca-mobile-${update.versionCode}.apk")
                val partial = File(dir, target.name + ".part")
                http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("The download got HTTP ${response.code}")
                    val body = response.body ?: throw IOException("The download was empty")
                    val total = body.contentLength()
                    var read = 0L
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                read += count
                                onProgress(read, total)
                            }
                        }
                    }
                    if (total > 0 && read != total) throw IOException("The download stopped early")
                }
                if (!partial.renameTo(target)) throw IOException("Could not keep the download")
                target.path
            }
        }

    override fun install(apkPath: String): Result<InstallStart> = runCatching {
        // Android asks once per app before it may install others.
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return@runCatching InstallStart.NeedsPermission
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", File(apkPath))
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw IOException("This phone has no app that installs APK files", e)
        }
        InstallStart.Started
    }

    companion object {
        const val RELEASES = "https://github.com/krishvish-0802/Orca_mobile/releases/download/"
        const val MAIN_TAG = "latest-build"
        private const val HTTP_NOT_FOUND = 404

        /** [tag]'s release, then main's when [tag] is another. */
        fun releaseUrlsFor(tag: String): List<String> = listOf(tag, MAIN_TAG).distinct().map { "$RELEASES$it/" }
        const val MANIFEST = "version.json"
        const val UPDATES_DIR = "updates"
        private const val APK_TYPE = "application/vnd.android.package-archive"
        private const val BUFFER_BYTES = 64 * 1024

        fun installedVersionCode(context: Context): Long =
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }
}
