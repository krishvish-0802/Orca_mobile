package com.orcamobile.core.data

import android.content.Context
import com.orcamobile.core.common.CoroutineDispatchers
import com.orcamobile.core.domain.presets.UserPresetRepository
import com.orcamobile.core.model.PresetKind
import com.orcamobile.core.model.UserPreset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's presets, one JSON file each under the app's files
 * (user_presets/<kind>/), as the desktop keeps them in its user folder.
 */
@Singleton
class FileUserPresetRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
) : UserPresetRepository {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val root get() = File(context.filesDir, "user_presets")

    override suspend fun all(): List<UserPreset> = withContext(dispatchers.io) {
        PresetKind.entries.flatMap { kind ->
            dir(kind).listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { file ->
                runCatching { json.decodeFromString<StoredPreset>(file.readText()) }.getOrNull()
                    ?.let { UserPreset(kind, it.name, it.inherits, it.values) }
            }
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun save(preset: UserPreset) = withContext(dispatchers.io) {
        val dir = dir(preset.kind).apply { mkdirs() }
        val target = File(dir, fileName(preset.name))
        val temp = File(dir, "${target.name}.tmp")
        temp.writeText(json.encodeToString(StoredPreset.serializer(), StoredPreset(preset.name, preset.inherits, preset.values)))
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
        Unit
    }

    override suspend fun delete(kind: PresetKind, name: String) = withContext(dispatchers.io) {
        File(dir(kind), fileName(name)).delete()
        Unit
    }

    private fun dir(kind: PresetKind) = File(root, kind.name.lowercase())

    /** A file name for a preset name: its letters kept, others encoded, so two names never share a file. */
    private fun fileName(name: String): String = buildString {
        for (c in name) {
            if (c.isLetterOrDigit() || c in " -_.@()") append(c) else append("%%%04x".format(c.code))
        }
        append(".json")
    }

    @Serializable
    private data class StoredPreset(val name: String, val inherits: String, val values: Map<String, String> = emptyMap())
}
