package com.orcamobile.core.engine

import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.engine.PresetCatalog
import com.orcamobile.core.engine.jni.NativeEngine
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.PresetSelection
import com.orcamobile.core.model.CompatiblePresets
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** [PresetCatalog] over the engine's PresetCatalog (native/bridge/src/PresetCatalog.cpp). */
@Singleton
class NativePresetCatalog @Inject constructor(
    private val environment: EngineEnvironment,
    private val dispatchers: CoroutineDispatchers,
) : PresetCatalog {

    override suspend fun vendors(): Result<List<String>> =
        call { EngineJson.stringList(NativeEngine.nativeListVendors()) }

    override suspend fun printers(vendor: String): Result<List<String>> =
        call { EngineJson.stringList(NativeEngine.nativeListPrinters(vendor)) }

    override suspend fun compatiblePresets(vendor: String, printer: String): Result<CompatiblePresets> =
        call { EngineJson.compatiblePresets(NativeEngine.nativeCompatiblePresets(vendor, printer)) }

    override suspend fun bedShape(vendor: String, printer: String): Result<BedShape> =
        call { EngineJson.bedShape(NativeEngine.nativePrinterBed(vendor, printer)) }

    override suspend fun filamentColours(selection: PresetSelection): Result<List<String>> = call {
        EngineJson.configValues(NativeEngine.nativeConfigValues(EngineJson.encode(selection), arrayOf(FILAMENT_COLOUR)))
            .map { values -> values[FILAMENT_COLOUR].orEmpty().split(';').filter { it.isNotBlank() } }
    }

    private suspend fun <T> call(query: () -> Result<T>): Result<T> = withContext(dispatchers.io) {
        if (!BuildConfig.NATIVE_ENGINE_BUNDLED) {
            return@withContext Result.failure(EngineException("This build does not include the native engine."))
        }
        NativeEngine.loadError?.let { return@withContext Result.failure(EngineException(it)) }
        runCatching { environment.ensureInitialized() }.fold({ query() }, { Result.failure(it) })
    }

    private companion object {
        const val FILAMENT_COLOUR = "filament_colour"
    }
}
