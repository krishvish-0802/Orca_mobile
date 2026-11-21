// Project natives of com.orcamobile.core.engine.jni.NativeEngine: a loaded model
// lives behind a jlong handle until nativeProjectRelease.
#include <jni.h>

#include <memory>

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

void native_project_release(JNIEnv*, jclass, jlong handle)
{
    delete from_handle<orca::engine::Project>(handle);
}

const JNINativeMethod kMethods[] = {
    {"nativeProjectLoad", "(Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(native_project_load)},
    {"nativeProjectObjects", "(J)Ljava/lang/String;", reinterpret_cast<void*>(native_project_objects)},
    {"nativeProjectCenterOn", "(JDD)V", reinterpret_cast<void*>(native_project_center_on)},
    {"nativeProjectRelease", "(J)V", reinterpret_cast<void*>(native_project_release)},
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
