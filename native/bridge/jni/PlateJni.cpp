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
#include "nlohmann/json.hpp"
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

// `options_json` may say {"allPlates": true}: pack every plate, adding plates as needed.
jstring native_plate_arrange(JNIEnv* env, jclass, jlong handle, jstring presets, jobjectArray keys,
                             jobjectArray values, jstring options_json)
{
    try {
        const PlateConfig config = plate_config(env, presets, keys, values);
        const std::string payload = to_std_string(env, options_json);
        const auto options = json::arrange_options_from_json(payload);
        const bool all_plates = !payload.empty() && nlohmann::json::parse(payload).value("allPlates", false);
        return plate_call(env, handle, [&](Project& project) {
            return orca::engine::arrange_plates(project, config, options, catalog(), all_plates);
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

// Split to objects: {"placed": how many objects it made, "objects": [...]}.
jstring native_plate_split(JNIEnv* env, jclass, jlong handle, jint object)
{
    if (object < 0)
        return to_jstring(env, json::error_json("No such object on the plate"));
    return plate_call(env, handle, [&](Project& project) {
        PlateChange change;
        change.placed = project.split_to_objects(std::size_t(object)).size();
        return change;
    });
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

// The current plate's prime tower as the plate shows it:
// {"x","y","width","depth","height","brim","rotation","overlaps"}, {} when the
// plate prints none, or {"error"}.
jstring native_plate_wipe_tower(JNIEnv* env, jclass, jlong handle, jstring presets, jobjectArray keys, jobjectArray values)
{
    auto* project = from_handle<Project>(handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        const PlateConfig config = plate_config(env, presets, keys, values);
        const auto tower = orca::engine::wipe_tower(*project, config, catalog());
        nlohmann::json out = nlohmann::json::object();
        if (tower)
            out = {{"x", tower->x}, {"y", tower->y}, {"width", tower->width}, {"depth", tower->depth},
                   {"height", tower->height}, {"brim", tower->brim}, {"rotation", tower->rotation_deg},
                   {"overlaps", tower->overlaps_objects}};
        return to_jstring(env, out.dump());
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

std::string plates_json(const Project& project)
{
    nlohmann::json plates = nlohmann::json::array();
    for (const orca::engine::PlateSummary& p : project.plates())
        plates.push_back({{"name", p.name}, {"settings", p.settings}, {"instances", p.instance_count}});
    nlohmann::json out = nlohmann::json::parse(json::to_json(project.objects()));
    out["current"] = project.current_plate();
    out["plates"] = plates;
    return out.dump();
}

// Plates: `action_json` is {"action": "get" | "select" | "add" | "remove" |
// "rename" | "set" | "reset" | "move", "plate", "name", "key", "value",
// "object", "instance"}. Returns {"current", "plates": [{"name", "settings",
// "instances"}], "objects": [...]}, or {"error"}.
jstring native_plates(JNIEnv* env, jclass, jlong handle, jstring action_json)
{
    auto* project = from_handle<Project>(handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        const nlohmann::json a = nlohmann::json::parse(to_std_string(env, action_json));
        const std::string action = a.value("action", std::string("get"));
        const auto plate = [&] { return a.at("plate").get<std::size_t>(); };
        if (action == "select")
            project->select_plate(plate());
        else if (action == "add")
            project->add_plate();
        else if (action == "remove")
            project->remove_plate(plate());
        else if (action == "rename")
            project->rename_plate(plate(), a.at("name").get<std::string>());
        else if (action == "set")
            project->set_plate_setting(plate(), a.at("key").get<std::string>(), a.at("value").get<std::string>());
        else if (action == "reset")
            project->reset_plate_setting(plate(), a.at("key").get<std::string>());
        else if (action == "move")
            project->move_to_plate({a.at("object").get<std::size_t>(), a.at("instance").get<std::size_t>()}, plate());
        else if (action != "get")
            throw std::invalid_argument("Unknown plate action: " + action);
        return to_jstring(env, plates_json(*project));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

#define STR "Ljava/lang/String;"
const JNINativeMethod kMethods[] = {
    {"nativePlateArrange", "(J" STR "[" STR "[" STR STR ")" STR, reinterpret_cast<void*>(native_plate_arrange)},
    {"nativePlateOrient", "(JII" STR "[" STR "[" STR ")" STR, reinterpret_cast<void*>(native_plate_orient)},
    {"nativePlateAddCopies", "(JIII" STR "[" STR "[" STR STR ")" STR, reinterpret_cast<void*>(native_plate_add_copies)},
    {"nativeProjectRemove", "(JII)" STR, reinterpret_cast<void*>(native_project_remove)},
    {"nativePlateSplit", "(JI)" STR, reinterpret_cast<void*>(native_plate_split)},
    {"nativePlates", "(J" STR ")" STR, reinterpret_cast<void*>(native_plates)},
    {"nativePlateWipeTower", "(J" STR "[" STR "[" STR ")" STR, reinterpret_cast<void*>(native_plate_wipe_tower)},
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
