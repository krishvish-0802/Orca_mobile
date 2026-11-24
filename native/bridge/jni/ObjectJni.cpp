// Object list natives of com.orcamobile.core.engine.jni.NativeEngine: an
// object's volumes, settings and height ranges. Both calls return the object
// after the call as {"object":{..},"objects":[...]}, or {"error":...}.
#include <jni.h>

#include <array>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

#include "EngineState.hpp"
#include "JniRegistry.hpp"
#include "JniUtils.hpp"
#include "nlohmann/json.hpp"
#include "orca_engine/Json.hpp"
#include "orca_engine/MeshTools.hpp"
#include "orca_engine/Painting.hpp"
#include "orca_engine/Project.hpp"

namespace orca::jni {

namespace {

namespace json = orca::engine::json;
using orca::engine::Project;
using orca::engine::SettingTarget;
using orca::engine::VolumeKind;

std::string details_json(const Project& project, std::size_t object)
{
    const orca::engine::ObjectDetails details = project.details(object);
    std::vector<orca::engine::InstanceTransform> transforms;
    for (std::size_t v = 0; v < details.volumes.size(); ++v)
        transforms.push_back(project.volume_transform(object, v));
    return json::to_json(details, transforms, project.objects());
}

std::optional<std::size_t> optional_index(const nlohmann::json& j, const char* key)
{
    if (!j.contains(key) || j.at(key).is_null())
        return std::nullopt;
    return j.at(key).get<std::size_t>();
}

SettingTarget target_of(std::size_t object, const nlohmann::json& j)
{
    return {object, optional_index(j, "volume"), optional_index(j, "range")};
}

// One edit, as ObjectEdit.kt encodes it: {"<name>": {arguments}}.
void apply_edit(Project& project, std::size_t object, const nlohmann::json& edit)
{
    if (!edit.is_object() || edit.size() != 1)
        throw std::invalid_argument("An object edit names one change: " + edit.dump());
    const std::string name = edit.begin().key();
    const nlohmann::json& a = edit.begin().value();
    const auto kind = [&] { return static_cast<VolumeKind>(a.at("kind").get<int>()); };
    if (name == "addPrimitive") {
        project.add_primitive(object, kind(), static_cast<orca::engine::PrimitiveShape>(a.at("shape").get<int>()),
                              a.at("size").get<double>());
    } else if (name == "addFile") {
        const std::string path = a.at("path").get<std::string>();
        run_on_engine_thread([&] { project.add_volume_from_file(object, kind(), path); });
    } else if (name == "splitToParts") {
        run_on_engine_thread([&] { project.split_to_parts(object, a.at("volume").get<std::size_t>()); });
    } else if (name == "simplify") {
        const auto request = json::simplify_request_from_json(object, a.dump());
        run_on_engine_thread([&] { orca::engine::simplify(project, request); });
    } else if (name == "boolean") {
        orca::engine::BooleanRequest request;
        request.object = object;
        request.source = a.at("source").get<std::size_t>();
        request.tool = a.at("tool").get<std::size_t>();
        const int operation = a.at("operation").get<int>();
        if (operation < 0 || operation > 2)
            throw std::invalid_argument("No such boolean operation");
        request.operation = static_cast<orca::engine::BooleanOperation>(operation);
        request.delete_tool = a.value("deleteTool", false);
        run_on_engine_thread([&] { orca::engine::mesh_boolean(project, request); });
    } else if (name == "removeVolume") {
        project.remove_volume(object, a.at("volume").get<std::size_t>());
    } else if (name == "setKind") {
        project.set_volume_kind(object, a.at("volume").get<std::size_t>(), kind());
    } else if (name == "rename") {
        project.rename(object, optional_index(a, "volume"), a.at("name").get<std::string>());
    } else if (name == "volumeTransform") {
        project.set_volume_transform(object, a.at("volume").get<std::size_t>(),
                                     a.at("position").get<std::array<double, 3>>(),
                                     a.at("rotation").get<std::array<double, 3>>(),
                                     a.at("scale").get<std::array<double, 3>>());
    } else if (name == "set") {
        project.set_setting(target_of(object, a), a.at("key").get<std::string>(), a.at("value").get<std::string>());
    } else if (name == "paintedDepth") {
        project.set_painted_depth(object, a.at("volume").get<std::size_t>(), a.at("depth").get<double>());
    } else if (name == "reset") {
        project.reset_setting(target_of(object, a), a.at("key").get<std::string>());
    } else if (name == "addRange") {
        project.add_layer_range(object, a.at("min").get<double>(), a.at("max").get<double>(),
                                a.at("layerHeight").get<std::string>());
    } else if (name == "setRange") {
        project.set_layer_range(object, a.at("range").get<std::size_t>(), a.at("min").get<double>(),
                                a.at("max").get<double>());
    } else if (name == "removeRange") {
        project.remove_layer_range(object, a.at("range").get<std::size_t>());
    } else {
        throw std::invalid_argument("Unknown object edit: " + name);
    }
}

jstring native_object_details(JNIEnv* env, jclass, jlong handle, jint object)
{
    auto* project = from_handle<Project>(handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        if (object < 0)
            throw std::out_of_range("No such object on the plate");
        return to_jstring(env, details_json(*project, std::size_t(object)));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

jstring native_object_edit(JNIEnv* env, jclass, jlong handle, jint object, jstring edit_json)
{
    auto* project = from_handle<Project>(handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        if (object < 0)
            throw std::out_of_range("No such object on the plate");
        apply_edit(*project, std::size_t(object), nlohmann::json::parse(to_std_string(env, edit_json)));
        return to_jstring(env, details_json(*project, std::size_t(object)));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// Paints every face of the object's parts with `state` for paint `kind`
// (PaintKind) and keeps it: true when that changed what will be sliced.
jboolean native_paint_everything(JNIEnv*, jclass, jlong handle, jint object, jint kind, jint state)
{
    auto* project = from_handle<Project>(handle);
    if (project == nullptr || object < 0 || kind < 0 || kind > 4)
        return JNI_FALSE;
    try {
        orca::engine::PaintSession session(*project, std::size_t(object), static_cast<orca::engine::PaintKind>(kind));
        session.paint_all(state);
        return session.commit() ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
}

// {"keys":[...]}: the settings an object (0), a part (1) or a height range (2) takes.
jstring native_setting_keys(JNIEnv* env, jclass, jint scope)
{
    if (scope < 0 || scope > 2)
        return to_jstring(env, json::error_json("No such setting scope"));
    return to_jstring(env, json::to_json(orca::engine::setting_keys(static_cast<orca::engine::SettingScope>(scope))));
}

#define STR "Ljava/lang/String;"
const JNINativeMethod kMethods[] = {
    {"nativeObjectDetails", "(JI)" STR, reinterpret_cast<void*>(native_object_details)},
    {"nativeObjectEdit", "(JI" STR ")" STR, reinterpret_cast<void*>(native_object_edit)},
    {"nativeSettingKeys", "(I)" STR, reinterpret_cast<void*>(native_setting_keys)},
    {"nativePaintEverything", "(JIII)Z", reinterpret_cast<void*>(native_paint_everything)},
};
#undef STR

} // namespace

bool register_object_natives(JNIEnv* env, jclass native_engine)
{
    if (env->RegisterNatives(native_engine, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) == 0)
        return true;
    env->ExceptionClear();
    return false;
}

} // namespace orca::jni
