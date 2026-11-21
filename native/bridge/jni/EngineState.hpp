#pragma once

#include <jni.h>

#include <string>

#include "JniUtils.hpp"
#include "orca_engine/PresetCatalog.hpp"
#include "orca_engine/SliceJob.hpp"

namespace orca::jni {

// The process-wide preset catalog, created by nativeInitialize. Null before.
orca::engine::PresetCatalog* catalog();
void reset_catalog(const std::string& profiles_dir);

// Forwards engine progress to a Kotlin NativeProgressListener. Runs on the
// slicing thread, which is the thread that called the slicing native, so the
// JNIEnv captured at the call is valid for every callback.
class JavaProgressSink final : public orca::engine::ProgressSink {
public:
    JavaProgressSink(JNIEnv* env, jobject listener);
    void on_progress(int percent, const std::string& message) override;

private:
    JNIEnv* m_env;
    jobject m_listener;
    jmethodID m_on_progress = nullptr;
};

} // namespace orca::jni
