#include "EngineState.hpp"

#include <memory>
#include <mutex>

namespace orca::jni {

namespace {
std::mutex g_catalog_mutex;
std::unique_ptr<orca::engine::PresetCatalog> g_catalog;
} // namespace

orca::engine::PresetCatalog* catalog()
{
    std::lock_guard<std::mutex> lock(g_catalog_mutex);
    return g_catalog.get();
}

void reset_catalog(const std::string& profiles_dir)
{
    std::lock_guard<std::mutex> lock(g_catalog_mutex);
    g_catalog = std::make_unique<orca::engine::PresetCatalog>(profiles_dir);
}

JavaProgressSink::JavaProgressSink(JNIEnv* env, jobject listener) : m_env(env), m_listener(listener)
{
    if (listener != nullptr) {
        LocalRef cls(env, env->GetObjectClass(listener));
        m_on_progress = env->GetMethodID(static_cast<jclass>(cls.get()), "onProgress", "(ILjava/lang/String;)V");
    }
}

void JavaProgressSink::on_progress(int percent, const std::string& message)
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

} // namespace orca::jni
