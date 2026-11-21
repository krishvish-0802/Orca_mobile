// JNI entry points for com.orcamobile.core.engine.jni.NativeEngine.
//
// Natives are registered in JNI_OnLoad, so they can stay hidden symbols and a
// renamed Kotlin method fails loudly at load time instead of at first call.
// Every call that can fail returns JSON produced by orca_engine::json; the
// Kotlin side owns parsing and error mapping.
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>

#include "JniUtils.hpp"
#include "orca_engine/EngineEnvironment.hpp"
#include "orca_engine/EngineInfo.hpp"
#include "orca_engine/Json.hpp"
#include "orca_engine/PresetCatalog.hpp"
#include "orca_engine/SliceJob.hpp"

namespace {

constexpr const char* kLogTag = "OrcaEngine";
constexpr const char* kNativeEngineClass = "com/orcamobile/core/engine/jni/NativeEngine";

using orca::jni::LocalRef;
using orca::jni::to_jstring;
using orca::jni::to_std_string;

// Forwards engine progress to a Kotlin NativeProgressListener. Runs on the
// slicing thread, which is the thread that called nativeSlice, so the JNIEnv
// captured at the call is valid for every callback.
class JavaProgressSink final : public orca::engine::ProgressSink {
public:
    JavaProgressSink(JNIEnv* env, jobject listener) : m_env(env), m_listener(listener)
    {
        if (listener != nullptr) {
            LocalRef cls(env, env->GetObjectClass(listener));
            m_on_progress = env->GetMethodID(static_cast<jclass>(cls.get()), "onProgress", "(ILjava/lang/String;)V");
        }
    }

    void on_progress(int percent, const std::string& message) override
    {
        if (m_listener == nullptr || m_on_progress == nullptr)
            return;
        LocalRef text(m_env, to_jstring(m_env, message));
        m_env->CallVoidMethod(m_listener, m_on_progress, static_cast<jint>(percent), text.get());
        if (m_env->ExceptionCheck()) {
            // A throwing listener must not unwind through libslic3r.
            m_env->ExceptionDescribe();
            m_env->ExceptionClear();
        }
    }

private:
    JNIEnv* m_env;
    jobject m_listener;
    jmethodID m_on_progress = nullptr;
};

std::map<std::string, std::string> to_override_map(JNIEnv* env, jobjectArray keys, jobjectArray values)
{
    std::map<std::string, std::string> overrides;
    if (keys == nullptr || values == nullptr)
        return overrides;
    const jsize count = std::min(env->GetArrayLength(keys), env->GetArrayLength(values));
    for (jsize i = 0; i < count; ++i) {
        LocalRef key(env, env->GetObjectArrayElement(keys, i));
        LocalRef value(env, env->GetObjectArrayElement(values, i));
        overrides[to_std_string(env, static_cast<jstring>(key.get()))] =
            to_std_string(env, static_cast<jstring>(value.get()));
    }
    return overrides;
}

// One catalog per process, created by nativeInitialize over
// <resources>/profiles. Loaded vendors stay cached in it.
std::mutex g_catalog_mutex;
std::unique_ptr<orca::engine::PresetCatalog> g_catalog;

orca::engine::PresetCatalog* catalog()
{
    std::lock_guard<std::mutex> lock(g_catalog_mutex);
    return g_catalog.get();
}

// Runs a catalog query and returns its JSON, or {"error": ...} on failure.
jstring catalog_call(JNIEnv* env, const std::function<std::string(orca::engine::PresetCatalog&)>& query)
{
    orca::engine::PresetCatalog* presets = catalog();
    if (presets == nullptr)
        return to_jstring(env, orca::engine::json::error_json("The engine is not initialized"));
    try {
        return to_jstring(env, query(*presets));
    } catch (const std::exception& e) {
        return to_jstring(env, orca::engine::json::error_json(e.what()));
    }
}

orca::engine::CancelToken* token_from_handle(jlong handle)
{
    return reinterpret_cast<orca::engine::CancelToken*>(static_cast<intptr_t>(handle));
}

// --- natives -----------------------------------------------------------

jstring native_engine_info_json(JNIEnv* env, jclass)
{
    return to_jstring(env, orca::engine::json::to_json(orca::engine::engine_info()));
}

void native_initialize(JNIEnv* env, jclass, jstring resources_dir, jstring data_dir, jstring temp_dir, jint log_level)
{
    const std::string resources = to_std_string(env, resources_dir);
    orca::engine::initialize_environment(
        {resources, to_std_string(env, data_dir), to_std_string(env, temp_dir)},
        static_cast<orca::engine::LogLevel>(log_level));
    {
        std::lock_guard<std::mutex> lock(g_catalog_mutex);
        g_catalog = std::make_unique<orca::engine::PresetCatalog>(resources + "/profiles");
    }
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Engine environment initialized");
}

jstring native_list_vendors(JNIEnv* env, jclass)
{
    return catalog_call(env, [](orca::engine::PresetCatalog& c) { return orca::engine::json::to_json(c.vendors()); });
}

jstring native_list_printers(JNIEnv* env, jclass, jstring vendor)
{
    const std::string id = to_std_string(env, vendor);
    return catalog_call(env, [&id](orca::engine::PresetCatalog& c) { return orca::engine::json::to_json(c.printers(id)); });
}

jstring native_compatible_presets(JNIEnv* env, jclass, jstring vendor, jstring printer)
{
    const std::string id = to_std_string(env, vendor);
    const std::string name = to_std_string(env, printer);
    return catalog_call(env, [&](orca::engine::PresetCatalog& c) {
        return orca::engine::json::to_json(c.compatible_presets(id, name));
    });
}

jlong native_create_cancel_token(JNIEnv*, jclass)
{
    return static_cast<jlong>(reinterpret_cast<intptr_t>(new orca::engine::CancelToken()));
}

void native_request_cancel(JNIEnv*, jclass, jlong handle)
{
    if (auto* token = token_from_handle(handle))
        token->request_cancel();
}

void native_release_cancel_token(JNIEnv*, jclass, jlong handle)
{
    delete token_from_handle(handle);
}

jstring native_slice(JNIEnv* env, jclass, jstring model_path, jstring output_path, jstring preset_selection_json,
                     jobjectArray keys, jobjectArray values, jobject listener, jlong cancel_handle)
{
    orca::engine::SliceRequest request;
    request.model_path = to_std_string(env, model_path);
    request.output_gcode_path = to_std_string(env, output_path);
    request.config_overrides = to_override_map(env, keys, values);
    try {
        request.presets = orca::engine::json::preset_selection_from_json(to_std_string(env, preset_selection_json));
    } catch (const std::exception& e) {
        orca::engine::SliceOutcome invalid;
        invalid.error = e.what();
        return to_jstring(env, orca::engine::json::to_json(invalid));
    }
    JavaProgressSink progress(env, listener);
    orca::engine::CancelToken fallback_token;
    orca::engine::CancelToken* token = token_from_handle(cancel_handle);
    const orca::engine::SliceOutcome outcome =
        orca::engine::run_slice(request, progress, token != nullptr ? *token : fallback_token, catalog());
    if (outcome.status == orca::engine::SliceOutcome::Status::Failed)
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Slice failed: %s", outcome.error.c_str());
    return to_jstring(env, orca::engine::json::to_json(outcome));
}

const JNINativeMethod kMethods[] = {
    {"nativeEngineInfoJson", "()Ljava/lang/String;", reinterpret_cast<void*>(native_engine_info_json)},
    {"nativeInitialize", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V",
     reinterpret_cast<void*>(native_initialize)},
    {"nativeListVendors", "()Ljava/lang/String;", reinterpret_cast<void*>(native_list_vendors)},
    {"nativeListPrinters", "(Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(native_list_printers)},
    {"nativeCompatiblePresets", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void*>(native_compatible_presets)},
    {"nativeCreateCancelToken", "()J", reinterpret_cast<void*>(native_create_cancel_token)},
    {"nativeRequestCancel", "(J)V", reinterpret_cast<void*>(native_request_cancel)},
    {"nativeReleaseCancelToken", "(J)V", reinterpret_cast<void*>(native_release_cancel_token)},
    {"nativeSlice",
     "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;"
     "Lcom/orcamobile/core/engine/jni/NativeProgressListener;J)Ljava/lang/String;",
     reinterpret_cast<void*>(native_slice)},
};

} // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*)
{
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;
    LocalRef cls(env, env->FindClass(kNativeEngineClass));
    if (cls.get() == nullptr)
        return JNI_ERR;
    if (env->RegisterNatives(static_cast<jclass>(cls.get()), kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != 0)
        return JNI_ERR;
    return JNI_VERSION_1_6;
}
