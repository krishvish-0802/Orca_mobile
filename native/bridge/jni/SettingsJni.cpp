// Settings natives, on com.orcamobile.core.engine.jni.NativeEngine: how settings
// depend on each other (OptionRules.hpp), and the user's own presets.
#include <jni.h>

#include <algorithm>
#include <exception>
#include <map>
#include <string>
#include <vector>

#include "EngineState.hpp"
#include "JniRegistry.hpp"
#include "JniUtils.hpp"
#include "nlohmann/json.hpp"
#include "orca_engine/Json.hpp"
#include "orca_engine/OptionRules.hpp"
#include "orca_engine/PresetCatalog.hpp"

namespace orca::jni {

namespace {

namespace json = orca::engine::json;
namespace engine = orca::engine;

nlohmann::json review_json(const engine::SettingsReview& review)
{
    nlohmann::json options = nlohmann::json::object();
    // Only what is not simply shown and editable.
    for (const auto& [key, state] : review.options)
        if (!state.visible || !state.enabled)
            options[key] = {{"visible", state.visible}, {"enabled", state.enabled}, {"reason", state.reason}};
    nlohmann::json checks = nlohmann::json::array();
    for (const auto& check : review.checks) {
        nlohmann::json fixes = nlohmann::json::array();
        for (const auto& fix : check.fixes)
            fixes.push_back({{"label", fix.label}, {"changes", fix.changes}});
        checks.push_back({{"id", check.id}, {"message", check.message}, {"fixes", fixes}});
    }
    return {{"options", options}, {"labels", review.labels}, {"changes", review.changes}, {"notices", review.notices}, {"checks", checks}};
}

// The rules for a preset selection (or {} for the engine defaults) with the
// settings `keys` = `values` over it, edited at `scope` (SettingsScope), showing
// filament slot `filament`. Returns {"options": {key: {"visible", "enabled",
// "reason"}}, "labels": {key: label}, "changes": {key: value}, "notices": [..],
// "checks": [{"id", "message", "fixes": [{"label", "changes": {..}}]}]} or {"error"}.
jstring native_review_settings(JNIEnv* env, jclass, jstring preset_selection_json, jobjectArray keys, jobjectArray values, jint scope,
                               jint filament)
{
    try {
        const auto selection = json::preset_selection_from_json(to_std_string(env, preset_selection_json));
        const auto overrides = to_string_map(env, keys, values);
        if (scope < 0 || scope > 2)
            return to_jstring(env, json::error_json("No such settings scope"));
        engine::SettingsReview review;
        run_on_engine_thread([&] {
            review = engine::review_settings(selection, overrides, static_cast<engine::SettingsScope>(scope), filament, catalog());
        });
        return to_jstring(env, review_json(review).dump());
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// The user's presets, replacing those given before: `presets_json` is
// [{"kind": 0 printer | 1 process | 2 filament, "name", "inherits", "values": {key: value}}].
// Returns {} or {"error"}.
jstring native_set_user_presets(JNIEnv* env, jclass, jstring presets_json)
{
    engine::PresetCatalog* presets = catalog();
    if (presets == nullptr)
        return to_jstring(env, json::error_json("The engine is not initialized"));
    try {
        std::vector<engine::UserPreset> list;
        for (const auto& p : nlohmann::json::parse(to_std_string(env, presets_json))) {
            engine::UserPreset preset;
            preset.kind = static_cast<engine::PresetKind>(std::clamp(p.value("kind", 1), 0, 2));
            preset.name = p.value("name", "");
            preset.inherits = p.value("inherits", "");
            preset.values = p.value("values", std::map<std::string, std::string>());
            list.push_back(std::move(preset));
        }
        presets->set_user_presets(std::move(list));
        return to_jstring(env, "{}");
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

#define STR "Ljava/lang/String;"
const JNINativeMethod kMethods[] = {
    {"nativeReviewSettings", "(" STR "[" STR "[" STR "II)" STR, reinterpret_cast<void*>(native_review_settings)},
    {"nativeSetUserPresets", "(" STR ")" STR, reinterpret_cast<void*>(native_set_user_presets)},
};
#undef STR

} // namespace

bool register_settings_natives(JNIEnv* env, jclass native_engine)
{
    if (env->RegisterNatives(native_engine, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) == 0)
        return true;
    env->ExceptionClear();
    return false;
}

} // namespace orca::jni
