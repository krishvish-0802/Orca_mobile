package com.orcamobile.core.data

import android.content.Context
import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.model.SampleModelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Copies sample models from the APK's assets to app storage on first use. */
@Singleton
class AssetSampleModelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
) : SampleModelRepository {

    override suspend fun calibrationCubePath(): String = materialize(CALIBRATION_CUBE)

    private suspend fun materialize(assetName: String): String = withContext(dispatchers.io) {
        val target = File(context.filesDir, assetName)
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            val partial = File(target.path + ".part")
            context.assets.open(assetName).use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            check(partial.renameTo(target)) { "Could not move $partial to $target" }
        }
        target.absolutePath
    }

    private companion object {
        const val CALIBRATION_CUBE = "samples/calibration_cube_20mm.stl"
    }
}
