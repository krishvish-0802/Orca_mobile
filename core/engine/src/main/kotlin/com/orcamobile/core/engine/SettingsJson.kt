package com.orcamobile.core.engine

import com.orcamobile.core.model.OptionRuleState
import com.orcamobile.core.model.SettingsCheck
import com.orcamobile.core.model.SettingsFix
import com.orcamobile.core.model.SettingsReview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The bridge's settings JSON (native/bridge/jni/SettingsJni.cpp). */
internal object SettingsJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun review(payload: String): Result<SettingsReview> = runCatching {
        val o = json.parseToJsonElement(payload).jsonObject
        if ("error" in o) throw EngineException(o.string("error") ?: "Unknown engine error")
        SettingsReview(
            options = o["options"]?.jsonObject.orEmpty().mapValues { (_, v) ->
                val s = v.jsonObject
                OptionRuleState(
                    visible = s.bool("visible") ?: true,
                    enabled = s.bool("enabled") ?: true,
                    reason = s.string("reason").orEmpty(),
                )
            },
            labels = o.strings("labels"),
            changes = o.strings("changes"),
            notices = o["notices"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            checks = o["checks"]?.jsonArray?.map { element ->
                val c = element.jsonObject
                SettingsCheck(
                    id = c.string("id").orEmpty(),
                    message = c.string("message").orEmpty(),
                    fixes = c["fixes"]?.jsonArray?.map { f ->
                        val fix = f.jsonObject
                        SettingsFix(fix.string("label").orEmpty(), fix.strings("changes"))
                    }.orEmpty(),
                )
            }.orEmpty(),
        )
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.strings(key: String): Map<String, String> =
        this[key]?.jsonObject.orEmpty().mapValues { (_, v) -> v.jsonPrimitive.contentOrNull.orEmpty() }
}
