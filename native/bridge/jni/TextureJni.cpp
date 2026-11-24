// Texture displacement natives, on com.orcamobile.core.engine.jni.NativeEngine:
// the object's layer stack, the textures shipped with the app, and the bake. On
// com.orcamobile.core.viewport.jni.NativeViewport: the relief previewed on the plate.
#include <jni.h>

#include <exception>
#include <map>
#include <memory>
#include <string>

#include "EngineState.hpp"
#include "JniRegistry.hpp"
#include "JniUtils.hpp"
#include "nlohmann/json.hpp"
#include "orca_engine/Json.hpp"
#include "orca_engine/Painting.hpp"
#include "orca_engine/Project.hpp"
#include "orca_engine/TextureDisplacement.hpp"
#include "orca_viewport/Viewport.hpp"

namespace orca::jni {

namespace {

namespace json = orca::engine::json;
namespace engine = orca::engine;
using orca::engine::Project;

nlohmann::json settings_json(const engine::TextureLayerSettings& s)
{
    return {
        {"depth", s.depth_mm},        {"tile", s.tile_mm},           {"rotation", s.rotation_deg},
        {"offsetX", s.offset_x},      {"offsetY", s.offset_y},       {"invert", s.invert},
        {"midlevel", s.midlevel},     {"smoothing", s.smoothing},    {"fadeEdge", s.fade_edge},
        {"edgeFade", s.edge_fade},    {"tiled", s.tiled},            {"mirrorTiles", s.mirror_tiles},
        {"projection", static_cast<int>(s.projection)},              {"blend", static_cast<int>(s.blend)},
    };
}

engine::TextureLayerSettings settings_from(const nlohmann::json& j)
{
    engine::TextureLayerSettings s;
    s.depth_mm = j.value("depth", s.depth_mm);
    s.tile_mm = j.value("tile", s.tile_mm);
    s.rotation_deg = j.value("rotation", s.rotation_deg);
    s.offset_x = j.value("offsetX", s.offset_x);
    s.offset_y = j.value("offsetY", s.offset_y);
    s.invert = j.value("invert", s.invert);
    s.midlevel = j.value("midlevel", s.midlevel);
    s.smoothing = j.value("smoothing", s.smoothing);
    s.fade_edge = j.value("fadeEdge", s.fade_edge);
    s.edge_fade = j.value("edgeFade", s.edge_fade);
    s.tiled = j.value("tiled", s.tiled);
    s.mirror_tiles = j.value("mirrorTiles", s.mirror_tiles);
    s.projection = static_cast<engine::TextureProjection>(j.value("projection", static_cast<int>(s.projection)));
    s.blend = static_cast<engine::TextureBlend>(j.value("blend", static_cast<int>(s.blend)));
    return s;
}

nlohmann::json stack_json(const engine::TextureStack& stack)
{
    nlohmann::json layers = nlohmann::json::array();
    for (const auto& l : stack.layers)
        layers.push_back({{"slot", l.slot},
                          {"name", l.name},
                          {"path", l.path},
                          {"width", l.width},
                          {"height", l.height},
                          {"hasColour", l.has_colour},
                          {"painted", l.painted},
                          {"settings", settings_json(l.settings)}});
    return {{"layers", layers},
            {"smooth", stack.smooth},
            {"smoothStrength", stack.smooth_strength},
            {"smoothPasses", stack.smooth_passes},
            {"displaceBorder", stack.displace_border},
            {"hasModelUv", stack.has_model_uv},
            {"full", stack.full}};
}

// The texture layer stack. `request_json`: {"action": "get" | "add" | "image" |
// "set" | "remove" | "move" | "options" | "paintAll", "slot", "path", "name",
// "settings": {...}, "by", "smooth", "smoothStrength", "smoothPasses",
// "displaceBorder", "erase"}. Returns the stack after the change, with "slot"
// for "add", or {"error"}.
jstring native_texture_stack(JNIEnv* env, jclass, jlong handle, jint object, jstring request_json)
{
    auto* project = from_handle<Project>(handle);
    if (project == nullptr || object < 0)
        return to_jstring(env, json::error_json("The model is no longer loaded"));
    try {
        const nlohmann::json r = nlohmann::json::parse(to_std_string(env, request_json));
        const std::string action = r.value("action", "get");
        const int slot = r.value("slot", -1);
        nlohmann::json added;
        run_on_engine_thread([&] {
            if (action == "add") {
                added = engine::add_texture_layer(*project, std::size_t(object), r.at("path").get<std::string>(), r.value("name", ""));
            } else if (action == "image") {
                engine::set_texture_image(*project, std::size_t(object), slot, r.at("path").get<std::string>(), r.value("name", ""));
            } else if (action == "set") {
                engine::set_texture_layer(*project, std::size_t(object), slot, settings_from(r.at("settings")));
            } else if (action == "remove") {
                engine::remove_texture_layer(*project, std::size_t(object), slot);
            } else if (action == "move") {
                engine::move_texture_layer(*project, std::size_t(object), slot, r.value("by", 0));
            } else if (action == "options") {
                engine::set_texture_options(*project, std::size_t(object), r.value("smooth", false), r.value("smoothStrength", 0.3f),
                                            r.value("smoothPasses", 2), r.value("displaceBorder", true));
            } else if (action == "paintAll") {
                // The whole object, or (erase) none of it, for this layer.
                engine::PaintSession session(*project, std::size_t(object), engine::PaintKind::Texture, std::size_t(slot));
                session.paint_all(r.value("erase", false) ? 0 : 1);
                session.commit();
            } else if (action != "get") {
                throw std::invalid_argument("No such texture action");
            }
        });
        nlohmann::json out = stack_json(engine::texture_stack(*project, std::size_t(object)));
        if (!added.is_null())
            out["slot"] = added;
        return to_jstring(env, out.dump());
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// Bakes the object's painted layers into its parts. Progress goes to `listener`
// (NativeProgressListener); `cancel_handle` stops it with nothing changed.
// Returns {"parts", "trianglesBefore", "trianglesAfter", "budgetLimited"},
// {"canceled": true} or {"error"}.
jstring native_texture_bake(JNIEnv* env, jclass, jlong handle, jint object, jobject listener, jlong cancel_handle)
{
    auto* project = from_handle<Project>(handle);
    if (project == nullptr || object < 0)
        return to_jstring(env, json::error_json("The model is no longer loaded"));
    auto* token = from_handle<engine::CancelToken>(cancel_handle);
    ProgressRelay relay(env, listener);
    engine::TextureBakeResult result;
    bool canceled = false;
    const engine::SliceOutcome outcome = relay.run([&] {
        result = engine::bake_texture(*project, std::size_t(object), [&](int percent) {
            if (token != nullptr && token->is_cancel_requested()) {
                canceled = true;
                return false;
            }
            relay.on_progress(percent, "Baking the texture");
            return true;
        });
        engine::SliceOutcome done;
        done.status = engine::SliceOutcome::Status::Succeeded;
        return done;
    });
    if (canceled)
        return to_jstring(env, nlohmann::json{{"canceled", true}}.dump());
    if (outcome.status != engine::SliceOutcome::Status::Succeeded)
        return to_jstring(env, json::error_json(outcome.error.empty() ? "The bake failed" : outcome.error));
    return to_jstring(env, nlohmann::json{{"parts", result.parts},
                                          {"trianglesBefore", result.triangles_before},
                                          {"trianglesAfter", result.triangles_after},
                                          {"budgetLimited", result.budget_limited}}
                               .dump());
}

// {"textures": [{"name", "path"}]}: the textures shipped with the app.
jstring native_texture_library(JNIEnv* env, jclass)
{
    try {
        nlohmann::json textures = nlohmann::json::array();
        for (const auto& t : engine::texture_library())
            textures.push_back({{"name", t.name}, {"path", t.path}});
        return to_jstring(env, nlohmann::json{{"textures", textures}}.dump());
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// A computed preview, until it is shown or dropped.
struct TexturePreview {
    int object = 0;
    std::vector<engine::TexturePreviewPart> parts;
};

// Computes the relief a bake of the object would make, off the project's lock
// and without a viewport (blocking: run it off the UI and GL threads). Returns a
// preview handle for nativeTexturePreviewShow / Free, or 0 when `cancel_handle`
// stopped it (or it failed): a newer preview is on its way, keep what is shown.
jlong native_texture_preview_compute(JNIEnv*, jclass, jlong project_handle, jint object, jlong cancel_handle)
{
    auto* project = from_handle<Project>(project_handle);
    if (project == nullptr || object < 0)
        return 0;
    auto* token = from_handle<engine::CancelToken>(cancel_handle);
    try {
        std::optional<std::vector<engine::TexturePreviewPart>> parts;
        run_on_engine_thread([&] {
            parts = engine::texture_preview(*project, std::size_t(object), [&] { return token == nullptr || !token->is_cancel_requested(); });
        });
        if (!parts)
            return 0;
        auto preview = std::make_unique<TexturePreview>();
        preview->object = object;
        preview->parts = std::move(*parts);
        return to_handle(preview.release());
    } catch (const std::exception&) {
        return 0;
    }
}

// Shows a computed preview on the viewport in place of its parts (none painted: none shown).
void native_texture_preview_show(JNIEnv*, jclass, jlong viewport_handle, jlong preview_handle)
{
    auto* v = from_handle<orca::viewport::Viewport>(viewport_handle);
    auto* preview = from_handle<TexturePreview>(preview_handle);
    if (v == nullptr || preview == nullptr)
        return;
    std::map<int, std::shared_ptr<const orca::viewport::PaintOverlayMesh>> meshes;
    for (const auto& part : preview->parts) {
        auto mesh = std::make_shared<orca::viewport::PaintOverlayMesh>();
        mesh->positions = part.positions;
        meshes[int(part.volume)] = std::move(mesh);
    }
    v->set_volume_previews(preview->object, std::move(meshes));
}

void native_texture_preview_free(JNIEnv*, jclass, jlong preview_handle)
{
    delete from_handle<TexturePreview>(preview_handle);
}

// Triangles in a computed preview, over its parts.
jlong native_texture_preview_triangles(JNIEnv*, jclass, jlong preview_handle)
{
    auto* preview = from_handle<TexturePreview>(preview_handle);
    if (preview == nullptr)
        return 0;
    std::size_t triangles = 0;
    for (const auto& part : preview->parts)
        triangles += part.positions.size() / 9;
    return jlong(triangles);
}

void native_texture_preview_clear(JNIEnv*, jclass, jlong viewport_handle)
{
    if (auto* v = from_handle<orca::viewport::Viewport>(viewport_handle))
        v->clear_volume_previews();
}

const JNINativeMethod kViewMethods[] = {
    {"nativeTexturePreviewShow", "(JJ)V", reinterpret_cast<void*>(native_texture_preview_show)},
    {"nativeTexturePreviewClear", "(J)V", reinterpret_cast<void*>(native_texture_preview_clear)},
};

const JNINativeMethod kMethods[] = {
    {"nativeTextureStack", "(JILjava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(native_texture_stack)},
    {"nativeTextureBake", "(JILcom/orcamobile/core/engine/jni/NativeProgressListener;J)Ljava/lang/String;",
     reinterpret_cast<void*>(native_texture_bake)},
    {"nativeTextureLibrary", "()Ljava/lang/String;", reinterpret_cast<void*>(native_texture_library)},
    {"nativeTexturePreviewCompute", "(JIJ)J", reinterpret_cast<void*>(native_texture_preview_compute)},
    {"nativeTexturePreviewTriangles", "(J)J", reinterpret_cast<void*>(native_texture_preview_triangles)},
    {"nativeTexturePreviewFree", "(J)V", reinterpret_cast<void*>(native_texture_preview_free)},
};

} // namespace

bool register_texture_view_natives(JNIEnv* env, jclass native_viewport)
{
    if (env->RegisterNatives(native_viewport, kViewMethods, sizeof(kViewMethods) / sizeof(kViewMethods[0])) == 0)
        return true;
    env->ExceptionClear();
    return false;
}

bool register_texture_natives(JNIEnv* env, jclass native_engine)
{
    if (env->RegisterNatives(native_engine, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) == 0)
        return true;
    env->ExceptionClear();
    return false;
}

} // namespace orca::jni
