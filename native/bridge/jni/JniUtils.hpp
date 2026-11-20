#pragma once

#include <jni.h>

#include <string>

namespace orca::jni {

// Copies a Java string into UTF-8. Returns an empty string for null.
inline std::string to_std_string(JNIEnv* env, jstring value)
{
    if (value == nullptr)
        return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars != nullptr ? chars : "");
    if (chars != nullptr)
        env->ReleaseStringUTFChars(value, chars);
    return result;
}

inline jstring to_jstring(JNIEnv* env, const std::string& value)
{
    return env->NewStringUTF(value.c_str());
}

// Holds a local reference and deletes it on scope exit. Long loops that create
// references would otherwise overflow the local reference table.
class LocalRef {
public:
    LocalRef(JNIEnv* env, jobject ref) : m_env(env), m_ref(ref) {}
    ~LocalRef() { if (m_ref != nullptr) m_env->DeleteLocalRef(m_ref); }
    LocalRef(const LocalRef&) = delete;
    LocalRef& operator=(const LocalRef&) = delete;
    jobject get() const { return m_ref; }

private:
    JNIEnv* m_env;
    jobject m_ref;
};

} // namespace orca::jni
