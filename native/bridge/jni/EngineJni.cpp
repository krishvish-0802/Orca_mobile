// Engine natives of com.orcamobile.core.engine.jni.NativeEngine: environment,
// preset catalog, cancel tokens and slicing. Calls that can fail return JSON
// produced by orca_engine::json; the Kotlin side owns parsing and error mapping.
#include <android/log.h>
#include <jni.h>

#include <functional>
#include <string>

#include "EngineState.hpp"
#include "JniRegistry.hpp"
#include "JniUtils.hpp"
#include "orca_engine/EngineEnvironment.hpp"
#include "orca_engine/EngineInfo.hpp"
#include "orca_engine/Json.hpp"
#include "orca_engine/PresetCatalog.hpp"
#include "orca_engine/Project.hpp"
#include "orca_engine/SliceJob.hpp"

namespace orca::jni {

namespace {

constexpr const char* kLogTag = "OrcaEngine";
namespace json = orca::engine::json;

// Runs a catalog query and returns its JSON, or {"error": ...} on failure.
jstring catalog_call(JNIEnv* env, const std::function<std::string(orca::engine::PresetCatalog&)>& query)
{
    orca::engine::PresetCatalog* presets = catalog();
    if (presets == nullptr)
        return to_jstring(env, json::error_json("The engine is not initialized"));
    try {
        return to_jstring(env, query(*presets));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

jstring native_engine_info_json(JNIEnv* env, jclass)
{
    return to_jstring(env, json::to_json(orca::engine::engine_info()));
}

void native_initialize(JNIEnv* env, jclass, jstring resources_dir, jstring data_dir, jstring temp_dir, jint log_level)
{
    const std::string resources = to_std_string(env, resources_dir);
    orca::engine::initialize_environment(
        {resources, to_std_string(env, data_dir), to_std_string(env, temp_dir)},
        static_cast<orca::engine::LogLevel>(log_level));
    reset_catalog(resources + "/profiles");
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Engine environment initialized");
}

jstring native_list_vendors(JNIEnv* env, jclass)
{
    return catalog_call(env, [](orca::engine::PresetCatalog& c) { return json::to_json(c.vendors()); });
}

jstring native_list_printers(JNIEnv* env, jclass, jstring vendor)
{
    const std::string id = to_std_string(env, vendor);
    return catalog_call(env, [&id](orca::engine::PresetCatalog& c) { return json::to_json(c.printers(id)); });
}

jstring native_compatible_presets(JNIEnv* env, jclass, jstring vendor, jstring printer)
{
    const std::string id = to_std_string(env, vendor);
    const std::string name = to_std_string(env, printer);
    return catalog_call(env, [&](orca::engine::PresetCatalog& c) { return json::to_json(c.compatible_presets(id, name)); });
}

jstring native_printer_bed(JNIEnv* env, jclass, jstring vendor, jstring printer)
{
    const std::string id = to_std_string(env, vendor);
    const std::string name = to_std_string(env, printer);
    return catalog_call(env, [&](orca::engine::PresetCatalog& c) { return json::to_json(c.bed_shape(id, name)); });
}

jlong native_create_cancel_token(JNIEnv*, jclass)
{
    return to_handle(new orca::engine::CancelToken());
}

void native_request_cancel(JNIEnv*, jclass, jlong handle)
{
    if (auto* token = from_handle<orca::engine::CancelToken>(handle))
        token->request_cancel();
}

void native_release_cancel_token(JNIEnv*, jclass, jlong handle)
{
    delete from_handle<orca::engine::CancelToken>(handle);
}

// Builds the request shared by both slicing natives, or returns the failure JSON.
bool make_request(JNIEnv* env, jstring output_path, jstring preset_selection_json, jobjectArray keys,
                  jobjectArray values, orca::engine::SliceRequest& request, std::string& failure)
{
    request.output_gcode_path = to_std_string(env, output_path);
    request.config_overrides = to_string_map(env, keys, values);
    try {
        request.presets = json::preset_selection_from_json(to_std_string(env, preset_selection_json));
        return true;
    } catch (const std::exception& e) {
        orca::engine::SliceOutcome invalid;
        invalid.error = e.what();
        failure = json::to_json(invalid);
        return false;
    }
}

jstring finish_slice(JNIEnv* env, const orca::engine::SliceOutcome& outcome)
{
    if (outcome.status == orca::engine::SliceOutcome::Status::Failed)
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Slice failed: %s", outcome.error.c_str());
    return to_jstring(env, json::to_json(outcome));
}

jstring native_slice(JNIEnv* env, jclass, jstring model_path, jstring output_path, jstring preset_selection_json,
                     jobjectArray keys, jobjectArray values, jobject listener, jlong cancel_handle)
{
    orca::engine::SliceRequest request;
    std::string failure;
    if (!make_request(env, output_path, preset_selection_json, keys, values, request, failure))
        return to_jstring(env, failure);
    request.model_path = to_std_string(env, model_path);
    ProgressRelay progress(env, listener);
    orca::engine::CancelToken fallback;
    auto* token = from_handle<orca::engine::CancelToken>(cancel_handle);
    orca::engine::CancelToken& cancel = token ? *token : fallback;
    return finish_slice(env, progress.run([&] { return orca::engine::run_slice(request, progress, cancel, catalog()); }));
}

jstring native_slice_project(JNIEnv* env, jclass, jlong project_handle, jstring output_path,
                             jstring preset_selection_json, jobjectArray keys, jobjectArray values,
                             jobject listener, jlong cancel_handle)
{
    auto* project = from_handle<orca::engine::Project>(project_handle);
    if (project == nullptr) {
        orca::engine::SliceOutcome invalid;
        invalid.error = "The project is no longer loaded";
        return to_jstring(env, json::to_json(invalid));
    }
    orca::engine::SliceRequest request;
    std::string failure;
    if (!make_request(env, output_path, preset_selection_json, keys, values, request, failure))
        return to_jstring(env, failure);
    ProgressRelay progress(env, listener);
    orca::engine::CancelToken fallback;
    auto* token = from_handle<orca::engine::CancelToken>(cancel_handle);
    orca::engine::CancelToken& cancel = token ? *token : fallback;
    return finish_slice(env, progress.run([&] {
        return orca::engine::run_slice(*project, request, progress, cancel, catalog());
    }));
}

#define STR "Ljava/lang/String;"
#define LISTENER "Lcom/orcamobile/core/engine/jni/NativeProgressListener;"
const JNINativeMethod kMethods[] = {
    {"nativeEngineInfoJson", "()" STR, reinterpret_cast<void*>(native_engine_info_json)},
    {"nativeInitialize", "(" STR STR STR "I)V", reinterpret_cast<void*>(native_initialize)},
    {"nativeListVendors", "()" STR, reinterpret_cast<void*>(native_list_vendors)},
    {"nativeListPrinters", "(" STR ")" STR, reinterpret_cast<void*>(native_list_printers)},
    {"nativeCompatiblePresets", "(" STR STR ")" STR, reinterpret_cast<void*>(native_compatible_presets)},
    {"nativePrinterBed", "(" STR STR ")" STR, reinterpret_cast<void*>(native_printer_bed)},
    {"nativeCreateCancelToken", "()J", reinterpret_cast<void*>(native_create_cancel_token)},
    {"nativeRequestCancel", "(J)V", reinterpret_cast<void*>(native_request_cancel)},
    {"nativeReleaseCancelToken", "(J)V", reinterpret_cast<void*>(native_release_cancel_token)},
    {"nativeSlice", "(" STR STR STR "[" STR "[" STR LISTENER "J)" STR, reinterpret_cast<void*>(native_slice)},
    {"nativeSliceProject", "(J" STR STR "[" STR "[" STR LISTENER "J)" STR,
     reinterpret_cast<void*>(native_slice_project)},
};
#undef STR
#undef LISTENER

} // namespace

bool register_engine_natives(JNIEnv* env, jclass native_engine)
{
    if (env->RegisterNatives(native_engine, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) == 0)
        return true;
    env->ExceptionClear();
    return false;
}

} // namespace orca::jni
