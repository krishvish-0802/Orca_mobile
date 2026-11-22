// Plate tool natives of com.orcamobile.core.engine.jni.NativeEngine: arrange,
// auto-orient, copies / fill bed and removing instances. Each returns the
// plate after the change as {"placed":..,"notPlaced":..,"objects":[...]}, or
// {"error":...}.
#include <jni.h>

#include <functional>
#include <optional>
#include <string>

#include "EngineState.hpp"
#include "JniRegistry.hpp"
#include "JniUtils.hpp"
#include "orca_engine/Json.hpp"
#include "orca_engine/PlateTools.hpp"
#include "orca_engine/Project.hpp"

namespace orca::jni {

namespace {

namespace json = orca::engine::json;
using orca::engine::PlateChange;
using orca::engine::PlateConfig;
using orca::engine::Project;

// Runs `tool` on the engine thread against the project behind `handle`.
jstring plate_call(JNIEnv* env, jlong handle, const std::function<PlateChange(Project&)>& tool)
{
    auto* project = from_handle<Project>(handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        PlateChange change;
        run_on_engine_thread([&] { change = tool(*project); });
        return to_jstring(env, json::to_json(change, project->objects()));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

PlateConfig plate_config(JNIEnv* env, jstring preset_selection_json, jobjectArray keys, jobjectArray values)
{
    PlateConfig config;
    config.presets = json::preset_selection_from_json(to_std_string(env, preset_selection_json));
    config.overrides = to_string_map(env, keys, values);
    return config;
}

jstring native_plate_arrange(JNIEnv* env, jclass, jlong handle, jstring presets, jobjectArray keys,
                             jobjectArray values, jstring options_json)
{
    try {
        const PlateConfig config = plate_config(env, presets, keys, values);
        const auto options = json::arrange_options_from_json(to_std_string(env, options_json));
        return plate_call(env, handle, [&](Project& project) {
            return orca::engine::arrange(project, config, options, catalog());
        });
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// object < 0: every instance.
jstring native_plate_orient(JNIEnv* env, jclass, jlong handle, jint object, jint instance, jstring presets,
                            jobjectArray keys, jobjectArray values)
{
    try {
        const PlateConfig config = plate_config(env, presets, keys, values);
        std::optional<orca::engine::InstanceRef> only;
        if (object >= 0)
            only = orca::engine::InstanceRef{std::size_t(object), std::size_t(instance)};
        return plate_call(env, handle, [&](Project& project) {
            orca::engine::orient(project, only, config, catalog());
            return PlateChange{};
        });
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// count < 0: fill the bed.
jstring native_plate_add_copies(JNIEnv* env, jclass, jlong handle, jint object, jint instance, jint count,
                                jstring presets, jobjectArray keys, jobjectArray values, jstring options_json)
{
    try {
        if (object < 0 || instance < 0)
            return to_jstring(env, json::error_json("No such instance on the plate"));
        const PlateConfig config = plate_config(env, presets, keys, values);
        const auto options = json::arrange_options_from_json(to_std_string(env, options_json));
        const orca::engine::InstanceRef source{std::size_t(object), std::size_t(instance)};
        const std::optional<std::size_t> copies = count < 0 ? std::nullopt : std::optional<std::size_t>(count);
        return plate_call(env, handle, [&](Project& project) {
            return orca::engine::add_copies(project, source, copies, config, options, catalog());
        });
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

jstring native_project_remove(JNIEnv* env, jclass, jlong handle, jint object, jint instance)
{
    if (object < 0 || instance < 0)
        return to_jstring(env, json::error_json("No such instance on the plate"));
    return plate_call(env, handle, [&](Project& project) {
        project.remove({std::size_t(object), std::size_t(instance)});
        return PlateChange{};
    });
}

#define STR "Ljava/lang/String;"
const JNINativeMethod kMethods[] = {
    {"nativePlateArrange", "(J" STR "[" STR "[" STR STR ")" STR, reinterpret_cast<void*>(native_plate_arrange)},
    {"nativePlateOrient", "(JII" STR "[" STR "[" STR ")" STR, reinterpret_cast<void*>(native_plate_orient)},
    {"nativePlateAddCopies", "(JIII" STR "[" STR "[" STR STR ")" STR, reinterpret_cast<void*>(native_plate_add_copies)},
    {"nativeProjectRemove", "(JII)" STR, reinterpret_cast<void*>(native_project_remove)},
};
#undef STR

} // namespace

bool register_plate_natives(JNIEnv* env, jclass native_engine)
{
    if (env->RegisterNatives(native_engine, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) == 0)
        return true;
    env->ExceptionClear();
    return false;
}

} // namespace orca::jni
