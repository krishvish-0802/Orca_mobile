#pragma once

#include <jni.h>

namespace orca::jni {

// Each file registers its natives on the given class. They return false (with
// the JNI exception cleared) if registration fails.
bool register_engine_natives(JNIEnv* env, jclass native_engine);
bool register_project_natives(JNIEnv* env, jclass native_engine);
bool register_plate_natives(JNIEnv* env, jclass native_engine);
bool register_preview_natives(JNIEnv* env, jclass native_engine);
bool register_viewport_natives(JNIEnv* env, jclass native_viewport);

} // namespace orca::jni
