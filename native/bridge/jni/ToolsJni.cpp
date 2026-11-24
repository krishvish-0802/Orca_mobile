// Object tool natives. On com.orcamobile.core.engine.jni.NativeEngine: variable
// layer height, brim ears and emboss. On com.orcamobile.core.viewport.jni.NativeViewport:
// brim ears shown on the plate and taps snapped to the object's outline.
#include <jni.h>

#include <array>
#include <cmath>
#include <exception>
#include <memory>
#include <optional>
#include <string>

#include "EngineState.hpp"
#include "JniRegistry.hpp"
#include "JniUtils.hpp"
#include "nlohmann/json.hpp"
#include "orca_engine/BrimEars.hpp"
#include "orca_engine/Emboss.hpp"
#include "orca_engine/Json.hpp"
#include "orca_engine/LayerHeights.hpp"
#include "orca_engine/Project.hpp"
#include "orca_viewport/Viewport.hpp"

namespace orca::jni {

namespace {

namespace json = orca::engine::json;
using orca::engine::Project;
using orca::viewport::Viewport;

orca::engine::PlateConfig plate_config(JNIEnv* env, jstring presets, jobjectArray keys, jobjectArray values)
{
    orca::engine::PlateConfig config;
    config.presets = json::preset_selection_from_json(to_std_string(env, presets));
    config.overrides = to_string_map(env, keys, values);
    return config;
}

std::string profile_json(const orca::engine::LayerProfile& p)
{
    return nlohmann::json{{"profile", p.profile},
                          {"layers", p.layers},
                          {"layerHeight", p.layer_height},
                          {"minLayerHeight", p.min_layer_height},
                          {"maxLayerHeight", p.max_layer_height},
                          {"objectHeight", p.object_height},
                          {"custom", p.custom}}
        .dump();
}

// Variable layer height. `action_json`: {"action": "get" | "adaptive" | "smooth" |
// "brush" | "reset", "quality", "radius", "keepMin", "z", "delta", "band", "brush"}.
jstring native_layer_profile(JNIEnv* env, jclass, jlong handle, jint object, jstring presets, jobjectArray keys,
                             jobjectArray values, jstring action_json)
{
    auto* project = from_handle<Project>(handle);
    if (project == nullptr || object < 0)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        const auto config = plate_config(env, presets, keys, values);
        const nlohmann::json a = nlohmann::json::parse(to_std_string(env, action_json));
        const std::string action = a.value("action", std::string("get"));
        const std::size_t o = std::size_t(object);
        auto* c = catalog();
        orca::engine::LayerProfile profile;
        run_on_engine_thread([&] {
            if (action == "get")
                profile = orca::engine::layer_profile(*project, o, config, c);
            else if (action == "adaptive")
                profile = orca::engine::adaptive_layer_profile(*project, o, config, c, a.value("quality", 0.5));
            else if (action == "smooth")
                profile = orca::engine::smooth_layer_profile(*project, o, config, c, a.value("radius", 5u), a.value("keepMin", false));
            else if (action == "brush") {
                const int brush = a.value("brush", 0);
                if (brush < 0 || brush > 3)
                    throw std::invalid_argument("No such brush");
                profile = orca::engine::brush_layer_profile(*project, o, config, c, a.at("z").get<double>(), a.at("delta").get<double>(),
                                                            a.value("band", 2.0), static_cast<orca::engine::LayerBrush>(brush));
            } else if (action == "reset")
                profile = orca::engine::reset_layer_profile(*project, o, config, c);
            else
                throw std::invalid_argument("Unknown layer height action: " + action);
        });
        return to_jstring(env, profile_json(profile));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

nlohmann::json ears_json(const std::vector<orca::engine::BrimEar>& ears)
{
    nlohmann::json out = nlohmann::json::array();
    for (const auto& e : ears)
        out.push_back({e.x, e.y, e.radius});
    return out;
}

std::vector<orca::engine::BrimEar> ears_from(const nlohmann::json& j)
{
    std::vector<orca::engine::BrimEar> ears;
    for (const auto& e : j)
        ears.push_back({e.at(0).get<double>(), e.at(1).get<double>(), e.at(2).get<double>()});
    return ears;
}

// Brim ears. `request_json`: {"action": "get" | "set" | "find", "ears": [[x, y, r]],
// "maxAngle", "detection", "radius"}. Returns {"ears": [[x, y, r]]}: the object's
// ears, or for "find" the ones found (not kept).
jstring native_brim_ears(JNIEnv* env, jclass, jlong handle, jint object, jint instance, jstring request_json)
{
    auto* project = from_handle<Project>(handle);
    if (project == nullptr || object < 0 || instance < 0)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        const nlohmann::json r = nlohmann::json::parse(to_std_string(env, request_json));
        const std::string action = r.value("action", std::string("get"));
        const orca::engine::InstanceRef via{std::size_t(object), std::size_t(instance)};
        std::vector<orca::engine::BrimEar> ears;
        run_on_engine_thread([&] {
            if (action == "set") {
                orca::engine::set_brim_ears(*project, via, ears_from(r.at("ears")));
                ears = orca::engine::brim_ears(*project, via);
            } else if (action == "find") {
                ears = orca::engine::find_brim_ears(*project, via, r.value("maxAngle", 125.0), r.value("detection", 1.0),
                                                    r.value("radius", 4.0));
            } else {
                ears = orca::engine::brim_ears(*project, via);
            }
        });
        return to_jstring(env, nlohmann::json{{"ears", ears_json(ears)}}.dump());
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

orca::engine::EmbossSource source_from(const nlohmann::json& j)
{
    orca::engine::EmbossSource s;
    s.text = j.value("text", std::string());
    s.font_path = j.value("font", std::string());
    s.letter_height_mm = j.value("height", s.letter_height_mm);
    s.char_gap = j.value("charGap", 0.0);
    s.line_gap = j.value("lineGap", 0.0);
    s.boldness_mm = j.value("boldness", 0.0);
    s.skew = j.value("skew", 0.0);
    s.svg_path = j.value("svg", std::string());
    s.svg_width_mm = j.value("svgWidth", 0.0);
    s.depth_mm = j.value("depth", s.depth_mm);
    return s;
}

// The shape's size: {"width","height"}, or {"error"}.
jstring native_emboss_size(JNIEnv* env, jclass, jstring source_json)
{
    try {
        const auto source = source_from(nlohmann::json::parse(to_std_string(env, source_json)));
        std::array<double, 2> size{};
        run_on_engine_thread([&] { size = orca::engine::emboss_size(source); });
        return to_jstring(env, nlohmann::json{{"width", size[0]}, {"height", size[1]}}.dump());
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// Embosses: {"source":{..}, "pick":{..}, "kind": n} onto the surface picked, or
// {"source":{..}, "x", "y"} as a new object. Returns {"placed": the new volume's
// or object's index, "objects":[..]}, or {"error"}.
jstring native_emboss(JNIEnv* env, jclass, jlong handle, jstring request_json)
{
    auto* project = from_handle<Project>(handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        const nlohmann::json r = nlohmann::json::parse(to_std_string(env, request_json));
        const auto source = source_from(r.at("source"));
        orca::engine::PlateChange change;
        if (r.contains("pick")) {
            orca::engine::FeatureFilter unused;
            const auto picks = json::feature_picks_from_json(nlohmann::json{{"picks", nlohmann::json::array({r["pick"]})}}.dump(), unused);
            const auto kind = static_cast<orca::engine::VolumeKind>(r.value("kind", 0));
            const bool surface = r.value("surface", false);
            run_on_engine_thread([&] { change.placed = orca::engine::emboss_on_surface(*project, picks.front(), kind, source, surface); });
        } else {
            run_on_engine_thread([&] { change.placed = orca::engine::emboss_object(*project, source, r.value("x", 0.0), r.value("y", 0.0)); });
        }
        return to_jstring(env, json::to_json(change, project->objects()));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

nlohmann::json source_json(const orca::engine::EmbossSource& s)
{
    return {{"text", s.text}, {"font", s.font_path}, {"height", s.letter_height_mm}, {"charGap", s.char_gap},
            {"lineGap", s.line_gap}, {"boldness", s.boldness_mm}, {"skew", s.skew}, {"svg", s.svg_path},
            {"svgWidth", s.svg_width_mm}, {"depth", s.depth_mm}};
}

// What volume `volume` of `object` was embossed from: {"source","kind","surface",
// "width","height"}; {} when it is no text or SVG; or {"error"}.
jstring native_embossed(JNIEnv* env, jclass, jlong handle, jint object, jint volume)
{
    auto* project = from_handle<Project>(handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        std::optional<orca::engine::EmbossedVolume> e;
        run_on_engine_thread([&] { e = orca::engine::embossed(*project, std::size_t(object), std::size_t(volume)); });
        if (!e)
            return to_jstring(env, "{}");
        return to_jstring(env, nlohmann::json{{"source", source_json(e->source)}, {"kind", static_cast<int>(e->kind)},
                                              {"surface", e->use_surface}, {"width", e->size[0]}, {"height", e->size[1]}}
                                   .dump());
    } catch (const std::exception& ex) {
        return to_jstring(env, json::error_json(ex.what()));
    }
}

// Makes an embossed volume anew: {"source":{..}, "kind": n, "surface": bool}.
// Returns {"objects":[..]} or {"error"}.
jstring native_emboss_update(JNIEnv* env, jclass, jlong handle, jint object, jint volume, jstring request_json)
{
    auto* project = from_handle<Project>(handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        const nlohmann::json r = nlohmann::json::parse(to_std_string(env, request_json));
        const auto source = source_from(r.at("source"));
        const auto kind = static_cast<orca::engine::VolumeKind>(r.value("kind", 0));
        const bool surface = r.value("surface", false);
        run_on_engine_thread([&] {
            orca::engine::emboss_update(*project, std::size_t(object), std::size_t(volume), kind, source, surface);
        });
        return to_jstring(env, json::to_json(orca::engine::PlateChange{}, project->objects()));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// Moves the text (volume `volume`) onto the surface at {"pick":{..}} on another
// part of its object. Returns {"objects":[..]} or {"error"}.
jstring native_emboss_move(JNIEnv* env, jclass, jlong handle, jint volume, jstring request_json)
{
    auto* project = from_handle<Project>(handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        const nlohmann::json r = nlohmann::json::parse(to_std_string(env, request_json));
        orca::engine::FeatureFilter unused;
        const auto picks = json::feature_picks_from_json(nlohmann::json{{"picks", nlohmann::json::array({r.at("pick")})}}.dump(), unused);
        run_on_engine_thread([&] {
            orca::engine::emboss_move(*project, std::size_t(volume), picks.front());
            orca::engine::emboss_settle(*project, picks.front().via.object);
        });
        return to_jstring(env, json::to_json(orca::engine::PlateChange{}, project->objects()));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// After a text was dragged: the object back on the bed. Returns {"objects":[..]} or {"error"}.
jstring native_emboss_settle(JNIEnv* env, jclass, jlong handle, jint object)
{
    auto* project = from_handle<Project>(handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        run_on_engine_thread([&] { orca::engine::emboss_settle(*project, std::size_t(object)); });
        return to_jstring(env, json::to_json(orca::engine::PlateChange{}, project->objects()));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// --- On the plate ---

void push(std::vector<float>& out, double x, double y, double z)
{
    out.push_back(float(x));
    out.push_back(float(y));
    out.push_back(float(z));
}

// Shows the object's outline on the bed and `ears_json` ([[x, y, r]]) as discs; `selected` (or -1) highlighted.
void native_brim_ears_show(JNIEnv* env, jclass, jlong viewport_handle, jlong project_handle, jint object, jint instance,
                           jstring ears_json_string, jint selected)
{
    auto* v = from_handle<Viewport>(viewport_handle);
    auto* project = from_handle<Project>(project_handle);
    if (v == nullptr || project == nullptr || object < 0 || instance < 0)
        return;
    try {
        const orca::engine::InstanceRef via{std::size_t(object), std::size_t(instance)};
        const auto ears = ears_from(nlohmann::json::parse(to_std_string(env, ears_json_string)));
        auto overlay = std::make_shared<orca::viewport::Overlay>();
        orca::viewport::Overlay::Shape outline;
        outline.positions = orca::engine::first_layer_outline(*project, via);
        outline.lines = true;
        outline.color = {0.95f, 0.30f, 0.20f, 1.0f};
        outline.on_top = true;
        overlay->shapes.push_back(std::move(outline));
        for (int pass = 0; pass < 2; ++pass) {
            orca::viewport::Overlay::Shape discs;
            discs.color = pass == 0 ? std::array<float, 4>{0.20f, 0.65f, 1.0f, 0.75f} : std::array<float, 4>{1.0f, 0.65f, 0.10f, 0.85f};
            for (std::size_t i = 0; i < ears.size(); ++i) {
                if ((int(i) == selected) != (pass == 1))
                    continue;
                const auto& e = ears[i];
                const int segments = 32;
                for (int k = 0; k < segments; ++k) {
                    const double a0 = 2 * M_PI * k / segments, a1 = 2 * M_PI * (k + 1) / segments;
                    push(discs.positions, e.x, e.y, 0.05);
                    push(discs.positions, e.x + e.radius * std::cos(a0), e.y + e.radius * std::sin(a0), 0.05);
                    push(discs.positions, e.x + e.radius * std::cos(a1), e.y + e.radius * std::sin(a1), 0.05);
                }
            }
            if (!discs.positions.empty())
                overlay->shapes.push_back(std::move(discs));
        }
        v->set_overlay(overlay);
        v->set_cut_view(std::nullopt);
    } catch (const std::exception&) {
        v->set_overlay(nullptr);
    }
}

// Where a screen point lands on the bed, snapped to the object's outline within
// `max_distance` mm: {"x","y"}, or {} when it is not near it.
jstring native_brim_ear_point(JNIEnv* env, jclass, jlong viewport_handle, jlong project_handle, jint object, jint instance,
                              jfloat x, jfloat y, jfloat max_distance)
{
    auto* v = from_handle<Viewport>(viewport_handle);
    auto* project = from_handle<Project>(project_handle);
    if (v == nullptr || project == nullptr || object < 0 || instance < 0)
        return to_jstring(env, "{}");
    try {
        const auto bed = v->bed_point(x, y);
        if (!bed)
            return to_jstring(env, "{}");
        const auto snapped = orca::engine::nearest_on_outline(*project, {std::size_t(object), std::size_t(instance)},
                                                              (*bed)[0], (*bed)[1], max_distance);
        if (!snapped)
            return to_jstring(env, nlohmann::json{{"bedX", (*bed)[0]}, {"bedY", (*bed)[1]}}.dump());
        return to_jstring(env, nlohmann::json{{"x", (*snapped)[0]}, {"y", (*snapped)[1]}, {"bedX", (*bed)[0]}, {"bedY", (*bed)[1]}}.dump());
    } catch (const std::exception&) {
        return to_jstring(env, "{}");
    }
}

#define STR "Ljava/lang/String;"
const JNINativeMethod kEngineMethods[] = {
    {"nativeLayerProfile", "(JI" STR "[" STR "[" STR STR ")" STR, reinterpret_cast<void*>(native_layer_profile)},
    {"nativeBrimEars", "(JII" STR ")" STR, reinterpret_cast<void*>(native_brim_ears)},
    {"nativeEmbossSize", "(" STR ")" STR, reinterpret_cast<void*>(native_emboss_size)},
    {"nativeEmboss", "(J" STR ")" STR, reinterpret_cast<void*>(native_emboss)},
    {"nativeEmbossed", "(JII)" STR, reinterpret_cast<void*>(native_embossed)},
    {"nativeEmbossUpdate", "(JII" STR ")" STR, reinterpret_cast<void*>(native_emboss_update)},
    {"nativeEmbossSettle", "(JI)" STR, reinterpret_cast<void*>(native_emboss_settle)},
    {"nativeEmbossMove", "(JI" STR ")" STR, reinterpret_cast<void*>(native_emboss_move)},
};
const JNINativeMethod kViewportMethods[] = {
    {"nativeBrimEarsShow", "(JJII" STR "I)V", reinterpret_cast<void*>(native_brim_ears_show)},
    {"nativeBrimEarPoint", "(JJIIFFF)" STR, reinterpret_cast<void*>(native_brim_ear_point)},
};
#undef STR

bool register_methods(JNIEnv* env, jclass type, const JNINativeMethod* methods, jint count)
{
    if (env->RegisterNatives(type, methods, count) == 0)
        return true;
    env->ExceptionClear();
    return false;
}

} // namespace

bool register_tools_natives(JNIEnv* env, jclass native_engine)
{
    return register_methods(env, native_engine, kEngineMethods, sizeof(kEngineMethods) / sizeof(kEngineMethods[0]));
}

bool register_tools_view_natives(JNIEnv* env, jclass native_viewport)
{
    return register_methods(env, native_viewport, kViewportMethods, sizeof(kViewportMethods) / sizeof(kViewportMethods[0]));
}

} // namespace orca::jni
