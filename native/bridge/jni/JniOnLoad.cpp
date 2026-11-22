// Registers every native method when liborca_engine.so loads. Natives are
// registered by name so they can stay hidden symbols, and a renamed Kotlin
// method fails loudly here instead of at first call.
#include <android/log.h>
#include <jni.h>

#include "JniRegistry.hpp"
#include "JniUtils.hpp"

namespace {

constexpr const char* kLogTag = "OrcaEngine";
constexpr const char* kNativeEngineClass = "com/orcamobile/core/engine/jni/NativeEngine";
constexpr const char* kNativeViewportClass = "com/orcamobile/core/viewport/jni/NativeViewport";

} // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*)
{
    using orca::jni::LocalRef;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;

    LocalRef engine(env, env->FindClass(kNativeEngineClass));
    if (engine.get() == nullptr) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s not found", kNativeEngineClass);
        return JNI_ERR;
    }
    const auto engine_class = static_cast<jclass>(engine.get());
    if (!orca::jni::register_engine_natives(env, engine_class) ||
        !orca::jni::register_project_natives(env, engine_class) ||
        !orca::jni::register_plate_natives(env, engine_class) ||
        !orca::jni::register_object_natives(env, engine_class) ||
        !orca::jni::register_preview_natives(env, engine_class)) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Registering engine natives failed");
        return JNI_ERR;
    }

    // The viewport is optional: an app (or test) without the viewport module
    // still gets a working engine.
    LocalRef viewport(env, env->FindClass(kNativeViewportClass));
    if (viewport.get() == nullptr) {
        env->ExceptionClear();
    } else if (!orca::jni::register_viewport_natives(env, static_cast<jclass>(viewport.get())) ||
               !orca::jni::register_paint_natives(env, static_cast<jclass>(viewport.get()))) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Registering viewport natives failed");
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
