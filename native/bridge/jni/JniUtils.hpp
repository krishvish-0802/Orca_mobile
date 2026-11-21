#pragma once

#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

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

// Pairs two parallel String[] arrays into a map (config key -> value).
inline std::map<std::string, std::string> to_string_map(JNIEnv* env, jobjectArray keys, jobjectArray values)
{
    std::map<std::string, std::string> result;
    if (keys == nullptr || values == nullptr)
        return result;
    const jsize count = std::min(env->GetArrayLength(keys), env->GetArrayLength(values));
    for (jsize i = 0; i < count; ++i) {
        LocalRef key(env, env->GetObjectArrayElement(keys, i));
        LocalRef value(env, env->GetObjectArrayElement(values, i));
        result[to_std_string(env, static_cast<jstring>(key.get()))] = to_std_string(env, static_cast<jstring>(value.get()));
    }
    return result;
}

// Copies a float[] into a vector.
inline std::vector<float> to_float_vector(JNIEnv* env, jfloatArray array)
{
    std::vector<float> result;
    if (array == nullptr)
        return result;
    result.resize(static_cast<size_t>(env->GetArrayLength(array)));
    env->GetFloatArrayRegion(array, 0, static_cast<jsize>(result.size()), result.data());
    return result;
}

// Native objects cross JNI as jlong handles holding their address.
template <typename T>
T* from_handle(jlong handle)
{
    return reinterpret_cast<T*>(static_cast<intptr_t>(handle));
}

template <typename T>
jlong to_handle(T* object)
{
    return static_cast<jlong>(reinterpret_cast<intptr_t>(object));
}

} // namespace orca::jni
