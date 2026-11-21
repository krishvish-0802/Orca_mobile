#pragma once

#include <jni.h>

#include <condition_variable>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <utility>

#include "JniUtils.hpp"
#include "orca_engine/PresetCatalog.hpp"
#include "orca_engine/SliceJob.hpp"

namespace orca::jni {

// The process-wide preset catalog, created by nativeInitialize. Null before.
orca::engine::PresetCatalog* catalog();
void reset_catalog(const std::string& profiles_dir);

// Runs `work` on a native thread with a desktop-sized stack (libslic3r recurses
// deeply; Java threads get about 1 MB) and waits for it. Exceptions escaping
// `work` are rethrown on the calling thread.
void run_on_engine_thread(const std::function<void()>& work);

// Engine progress for a Kotlin NativeProgressListener. libslic3r reports from
// its worker threads too (G-code generation runs in a TBB pipeline), and a
// JNIEnv is valid only on its own thread, so reports are queued here and the
// thread that called the native delivers them while the slice runs.
class ProgressRelay final : public orca::engine::ProgressSink {
public:
    ProgressRelay(JNIEnv* env, jobject listener);

    // Any thread.
    void on_progress(int percent, const std::string& message) override;

    // Runs `work` on an engine thread, delivering progress to the listener on the
    // calling thread until it finishes. Reports may be coalesced: the latest wins.
    orca::engine::SliceOutcome run(const std::function<orca::engine::SliceOutcome()>& work);

private:
    void deliver(int percent, const std::string& message);

    JNIEnv* m_env;
    jobject m_listener;
    jmethodID m_on_progress = nullptr;

    std::mutex m_mutex;
    std::condition_variable m_changed;
    std::optional<std::pair<int, std::string>> m_pending;
    bool m_done = false;
};

} // namespace orca::jni
