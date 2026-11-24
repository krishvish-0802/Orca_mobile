// Settings natives, on com.orcamobile.core.engine.jni.NativeEngine: how settings
// depend on each other (OptionRules.hpp).
#include <jni.h>

#include <exception>
#include <string>

#include "EngineState.hpp"
#include "JniRegistry.hpp"
#include "JniUtils.hpp"
#include "nlohmann/json.hpp"
#include "orca_engine/Json.hpp"
#include "orca_engine/OptionRules.hpp"

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

#define STR "Ljava/lang/String;"
const JNINativeMethod kMethods[] = {
    {"nativeReviewSettings", "(" STR "[" STR "[" STR "II)" STR, reinterpret_cast<void*>(native_review_settings)},
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
