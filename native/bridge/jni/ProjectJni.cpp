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

void native_project_release(JNIEnv*, jclass, jlong handle)
{
    delete from_handle<orca::engine::Project>(handle);
}

const JNINativeMethod kMethods[] = {
    {"nativeProjectLoad", "(Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(native_project_load)},
    {"nativeProjectObjects", "(J)Ljava/lang/String;", reinterpret_cast<void*>(native_project_objects)},
    {"nativeProjectCenterOn", "(JDD)V", reinterpret_cast<void*>(native_project_center_on)},
    {"nativeProjectRelease", "(J)V", reinterpret_cast<void*>(native_project_release)},
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
