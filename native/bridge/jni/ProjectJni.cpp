// Project natives of com.orcamobile.core.engine.jni.NativeEngine: a loaded model
// lives behind a jlong handle until nativeProjectRelease.
#include <jni.h>

#include <array>
#include <memory>
#include <stdexcept>
#include <string>

#include "EngineState.hpp"
#include "JniRegistry.hpp"
#include "JniUtils.hpp"
#include "nlohmann/json.hpp"
#include "orca_engine/Json.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "orca_engine/PresetFiles.hpp"
#include "orca_engine/Project.hpp"

namespace orca::jni {

namespace {

namespace json = orca::engine::json;

// {"handle":"<id>","objects":[...]} or {"error":...}. The handle travels as a
// string because JSON numbers cannot hold every 64-bit address exactly.
jstring native_project_load(JNIEnv* env, jclass, jstring path)
{
    try {
        const std::string file = to_std_string(env, path);
        std::unique_ptr<orca::engine::Project> project;
        run_on_engine_thread([&] { project = orca::engine::Project::load(file); });
        nlohmann::json result = nlohmann::json::parse(json::to_json(project->objects()));
        result["handle"] = std::to_string(to_handle(project.release()));
        return to_jstring(env, result.dump());
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

jstring native_project_objects(JNIEnv* env, jclass, jlong handle)
{
    auto* project = from_handle<orca::engine::Project>(handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    return to_jstring(env, json::to_json(project->objects()));
}

void native_project_center_on(JNIEnv*, jclass, jlong handle, jdouble x, jdouble y)
{
    if (auto* project = from_handle<orca::engine::Project>(handle))
        project->center_on(x, y);
}

// Undo and redo: `action` 0 records the project as it is now, 1 undoes, 2 redoes.
// Returns {"changed", "canUndo", "canRedo", "objects":[..]} or {"error"}.
jstring native_project_history(JNIEnv* env, jclass, jlong handle, jint action)
{
    auto* project = from_handle<orca::engine::Project>(handle);
    if (project == nullptr)
        return to_jstring(env, orca::engine::json::error_json("The project is no longer loaded"));
    try {
        bool changed = false;
        if (action == 0)
            project->record_history();
        else if (action == 1)
            changed = project->undo();
        else if (action == 2)
            changed = project->redo();
        nlohmann::json out = nlohmann::json::parse(orca::engine::json::to_json(project->objects()));
        out["changed"] = changed;
        out["canUndo"] = project->can_undo();
        out["canRedo"] = project->can_redo();
        return to_jstring(env, out.dump());
    } catch (const std::exception& e) {
        return to_jstring(env, orca::engine::json::error_json(e.what()));
    }
}

// The filaments changed: `removed` (0-based, or -1) was taken away and `count`
// are left.
void native_project_filaments(JNIEnv*, jclass, jlong handle, jint removed, jint count)
{
    auto* project = from_handle<orca::engine::Project>(handle);
    if (project == nullptr || count < 1)
        return;
    try {
        if (removed >= 0)
            project->remove_filament(std::size_t(removed), std::size_t(count));
        project->limit_filaments(std::size_t(count));
    } catch (const std::exception&) {
        // Nothing to undo: the model keeps what it had.
    }
}

// Reads, and with `edit_json` changes, an instance's transform:
// "" reads; {"position":[x,y]}, {"rotation":[x,y,z]} (degrees), {"scale":[x,y,z]},
// {"mirror":axis} or {"reset":true} change it first.
jstring native_instance_transform(JNIEnv* env, jclass, jlong handle, jint object, jint instance, jstring edit_json)
{
    auto* project = from_handle<orca::engine::Project>(handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    if (object < 0 || instance < 0)
        return to_jstring(env, json::error_json("No such instance on the plate"));
    const orca::engine::InstanceRef ref{std::size_t(object), std::size_t(instance)};
    try {
        const std::string payload = to_std_string(env, edit_json);
        if (!payload.empty()) {
            const nlohmann::json edit = nlohmann::json::parse(payload);
            if (edit.contains("position")) {
                const auto xy = edit.at("position").get<std::array<double, 2>>();
                project->set_position(ref, xy[0], xy[1]);
            } else if (edit.contains("rotation")) {
                project->set_rotation(ref, edit.at("rotation").get<std::array<double, 3>>());
            } else if (edit.contains("scale")) {
                project->set_scale(ref, edit.at("scale").get<std::array<double, 3>>());
            } else if (edit.contains("mirror")) {
                project->mirror(ref, edit.at("mirror").get<int>());
            } else if (edit.value("reset", false)) {
                project->reset_transform(ref);
            } else {
                throw std::invalid_argument("Unknown transform edit: " + payload);
            }
        }
        return to_jstring(env, json::to_json(project->transform(ref), project->objects()));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// Saves the project as the desktop's project file at `path`, with the settings of
// `presets` ({} for the defaults) and `keys` = `values` over them. Returns {} or {"error"}.
jstring native_project_save(JNIEnv* env, jclass, jlong handle, jstring path, jstring presets, jobjectArray keys, jobjectArray values)
{
    auto* project = from_handle<orca::engine::Project>(handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        const std::string file = to_std_string(env, path);
        const auto selection = json::preset_selection_from_json(to_std_string(env, presets));
        const auto overrides = to_string_map(env, keys, values);
        run_on_engine_thread([&] {
            const Slic3r::DynamicPrintConfig config = orca::engine::project_settings(catalog(), selection, overrides);
            project->save(file, config);
        });
        return to_jstring(env, "{}");
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// The presets the project file was saved with, over `vendor`'s: {"selection": {..} | null,
// "changes": {key: value}, "problems": [..]}; {"selection": null} for a file without them; or {"error"}.
jstring native_project_presets(JNIEnv* env, jclass, jlong handle, jstring vendor)
{
    auto* project = from_handle<orca::engine::Project>(handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        Slic3r::DynamicPrintConfig settings;
        if (!project->loaded_settings(settings) || catalog() == nullptr)
            return to_jstring(env, R"({"selection":null})");
        orca::engine::ProjectPresets presets;
        const std::string v = to_std_string(env, vendor);
        run_on_engine_thread([&] { presets = orca::engine::project_presets(*catalog(), v, settings); });
        const auto& s = presets.selection;
        const bool complete = !s.printer.empty() && !s.process.empty() && !s.filaments.empty();
        nlohmann::json selection = nullptr;
        if (complete)
            selection = {{"vendor", s.vendor}, {"printer", s.printer}, {"process", s.process}, {"filaments", s.filaments},
                         {"filamentColours", s.filament_colours}};
        return to_jstring(env, nlohmann::json{{"selection", selection}, {"changes", presets.changes}, {"problems", presets.problems}}.dump());
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

void native_project_release(JNIEnv*, jclass, jlong handle)
{
    delete from_handle<orca::engine::Project>(handle);
}

const JNINativeMethod kMethods[] = {
    {"nativeProjectLoad", "(Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(native_project_load)},
    {"nativeProjectObjects", "(J)Ljava/lang/String;", reinterpret_cast<void*>(native_project_objects)},
    {"nativeProjectCenterOn", "(JDD)V", reinterpret_cast<void*>(native_project_center_on)},
    {"nativeProjectRelease", "(J)V", reinterpret_cast<void*>(native_project_release)},
    {"nativeProjectPresets", "(JLjava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(native_project_presets)},
    {"nativeProjectSave", "(JLjava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void*>(native_project_save)},
    {"nativeProjectFilaments", "(JII)V", reinterpret_cast<void*>(native_project_filaments)},
    {"nativeProjectHistory", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(native_project_history)},
    {"nativeInstanceTransform", "(JIILjava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void*>(native_instance_transform)},
};

} // namespace

bool register_project_natives(JNIEnv* env, jclass native_engine)
{
    if (env->RegisterNatives(native_engine, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) == 0)
        return true;
    env->ExceptionClear();
    return false;
}

} // namespace orca::jni
