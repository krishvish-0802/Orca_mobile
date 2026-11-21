// G-code preview natives of com.orcamobile.core.engine.jni.NativeEngine: a
// processed G-code file lives behind a jlong handle until nativePreviewRelease.
#include <jni.h>

#include <memory>

#include "JniRegistry.hpp"
#include "JniUtils.hpp"
#include "nlohmann/json.hpp"
#include "orca_engine/GCodePreview.hpp"
#include "orca_engine/Json.hpp"

namespace orca::jni {

namespace {

namespace json = orca::engine::json;

// {"handle":"<id>","layerZs":[...],...} or {"error":...}. Blocking: reading a
// large file takes seconds, so callers run it off the main thread.
jstring native_preview_load(JNIEnv* env, jclass, jstring gcode_path)
{
    try {
        std::unique_ptr<orca::engine::GCodePreview> preview =
            orca::engine::GCodePreview::load(to_std_string(env, gcode_path));
        nlohmann::json result = nlohmann::json::parse(json::to_json(preview->summary()));
        result["handle"] = std::to_string(to_handle(preview.release()));
        return to_jstring(env, result.dump());
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

void native_preview_release(JNIEnv*, jclass, jlong handle)
{
    delete from_handle<orca::engine::GCodePreview>(handle);
}

const JNINativeMethod kMethods[] = {
    {"nativePreviewLoad", "(Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(native_preview_load)},
    {"nativePreviewRelease", "(J)V", reinterpret_cast<void*>(native_preview_release)},
};

} // namespace

bool register_preview_natives(JNIEnv* env, jclass native_engine)
{
    if (env->RegisterNatives(native_engine, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) == 0)
        return true;
    env->ExceptionClear();
    return false;
}

} // namespace orca::jni
