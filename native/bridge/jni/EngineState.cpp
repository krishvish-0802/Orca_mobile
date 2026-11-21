#include "EngineState.hpp"

#include <pthread.h>

#include <exception>
#include <memory>
#include <stdexcept>
#include <utility>

namespace orca::jni {

namespace {
std::mutex g_catalog_mutex;
std::unique_ptr<orca::engine::PresetCatalog> g_catalog;

// As a desktop Linux main thread; the CLI harness slices there.
constexpr size_t kEngineStackBytes = 16u * 1024u * 1024u;

// A native thread with kEngineStackBytes of stack, running `work`.
class EngineThread {
public:
    explicit EngineThread(std::function<void()> work) : m_work(std::move(work))
    {
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setstacksize(&attr, kEngineStackBytes);
        m_started = pthread_create(&m_thread, &attr, &EngineThread::main, this) == 0;
        pthread_attr_destroy(&attr);
        if (!m_started)
            main(this);   // no thread to be had: run here rather than not at all
    }
    EngineThread(const EngineThread&) = delete;
    EngineThread& operator=(const EngineThread&) = delete;

    // Waits for `work`, then rethrows what escaped it.
    void join()
    {
        if (m_started)
            pthread_join(m_thread, nullptr);
        m_started = false;
        if (m_error)
            std::rethrow_exception(std::exchange(m_error, nullptr));
    }

private:
    static void* main(void* self)
    {
        auto* thread = static_cast<EngineThread*>(self);
        try {
            thread->m_work();
        } catch (...) {
            thread->m_error = std::current_exception();
        }
        return nullptr;
    }

    std::function<void()> m_work;
    pthread_t m_thread{};
    bool m_started = false;
    std::exception_ptr m_error;
};
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

void run_on_engine_thread(const std::function<void()>& work)
{
    EngineThread thread(work);
    thread.join();
}

ProgressRelay::ProgressRelay(JNIEnv* env, jobject listener) : m_env(env), m_listener(listener)
{
    if (listener != nullptr) {
        LocalRef cls(env, env->GetObjectClass(listener));
        m_on_progress = env->GetMethodID(static_cast<jclass>(cls.get()), "onProgress", "(ILjava/lang/String;)V");
        if (env->ExceptionCheck())
            env->ExceptionClear();
    }
}

void ProgressRelay::on_progress(int percent, const std::string& message)
{
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_pending.emplace(percent, message);
    }
    m_changed.notify_one();
}

orca::engine::SliceOutcome ProgressRelay::run(const std::function<orca::engine::SliceOutcome()>& work)
{
    orca::engine::SliceOutcome outcome;
    EngineThread engine([&] {
        struct MarkDone {
            ProgressRelay& relay;
            ~MarkDone()
            {
                {
                    std::lock_guard<std::mutex> lock(relay.m_mutex);
                    relay.m_done = true;
                }
                relay.m_changed.notify_one();
            }
        } mark_done{*this};
        outcome = work();
    });

    // Deliver progress here, on the thread whose JNIEnv this is, until the engine is done.
    for (;;) {
        std::optional<std::pair<int, std::string>> report;
        bool done = false;
        {
            std::unique_lock<std::mutex> lock(m_mutex);
            m_changed.wait(lock, [&] { return m_pending.has_value() || m_done; });
            report = std::move(m_pending);
            m_pending.reset();
            done = m_done;
        }
        if (report)
            deliver(report->first, report->second);
        if (done)
            break;
    }

    try {
        engine.join();
    } catch (const std::exception& e) {
        outcome = {};
        outcome.error = e.what();
    } catch (...) {
        outcome = {};
        outcome.error = "The slicing engine failed unexpectedly";
    }
    return outcome;
}

void ProgressRelay::deliver(int percent, const std::string& message)
{
    if (m_listener == nullptr || m_on_progress == nullptr)
        return;
    LocalRef text(m_env, to_jstring(m_env, message));
    m_env->CallVoidMethod(m_listener, m_on_progress, static_cast<jint>(percent), text.get());
    if (m_env->ExceptionCheck()) {
        // A throwing listener must not stop the slice.
        m_env->ExceptionDescribe();
        m_env->ExceptionClear();
    }
}

} // namespace orca::jni
