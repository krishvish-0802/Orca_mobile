// Mesh tool natives. On com.orcamobile.core.engine.jni.NativeEngine: the cut
// itself and its checks. On com.orcamobile.core.viewport.jni.NativeViewport: the
// cut shown on the plate (plane, section, connectors) and taps on its plane.
#include <jni.h>

#include <array>
#include <exception>
#include <limits>
#include <memory>

#include "EngineState.hpp"
#include "JniRegistry.hpp"
#include "JniUtils.hpp"
#include "nlohmann/json.hpp"
#include "orca_engine/Json.hpp"
#include "orca_engine/MeshTools.hpp"
#include "orca_engine/Project.hpp"
#include "orca_viewport/Viewport.hpp"

namespace orca::jni {

namespace {

namespace json = orca::engine::json;
using orca::engine::Project;
using orca::viewport::Viewport;

// The checks without the geometry: {"canCut", "reason", "problems", ...}.
jstring native_cut_preview(JNIEnv* env, jclass, jlong project_handle, jstring request_json)
{
    auto* project = from_handle<Project>(project_handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        const auto request = json::cut_request_from_json(to_std_string(env, request_json));
        return to_jstring(env, json::to_json(orca::engine::cut_preview(*project, request)));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// Cuts: {"placed": how many pieces, "objects": [...]}, or {"error"}.
jstring native_cut_perform(JNIEnv* env, jclass, jlong project_handle, jstring request_json)
{
    auto* project = from_handle<Project>(project_handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        const auto request = json::cut_request_from_json(to_std_string(env, request_json));
        orca::engine::PlateChange change;
        run_on_engine_thread([&] { change.placed = orca::engine::cut(*project, request).size(); });
        return to_jstring(env, json::to_json(change, project->objects()));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

orca::viewport::Overlay::Shape shape(std::vector<float> positions, bool lines, std::array<float, 4> color, bool on_top = false)
{
    orca::viewport::Overlay::Shape s;
    s.positions = std::move(positions);
    s.lines = lines;
    s.color = color;
    s.on_top = on_top;
    return s;
}

// Shows the cut on the plate and returns its checks, as nativeCutPreview.
// `show_face`: nothing above the plane is drawn, so the cut face shows (for placing connectors).
jstring native_cut_show(JNIEnv* env, jclass, jlong viewport_handle, jlong project_handle, jstring request_json,
                        jboolean show_face)
{
    auto* v = from_handle<Viewport>(viewport_handle);
    auto* project = from_handle<Project>(project_handle);
    if (v == nullptr || project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        const auto request = json::cut_request_from_json(to_std_string(env, request_json));
        orca::engine::CutPreview preview = orca::engine::cut_preview(*project, request);
        auto overlay = std::make_shared<orca::viewport::Overlay>();
        overlay->shapes.push_back(shape(std::move(preview.section), false, {1.0f, 1.0f, 1.0f, 1.0f}));
        overlay->shapes.push_back(shape(std::move(preview.connectors), false, {0.55f, 0.85f, 0.35f, 1.0f}));
        overlay->shapes.push_back(shape(std::move(preview.outline), true, {0.95f, 0.30f, 0.20f, 1.0f}, true));
        overlay->shapes.push_back(shape(std::move(preview.plane), false,
                                        preview.can_cut ? std::array<float, 4>{0.95f, 0.75f, 0.20f, 0.30f}
                                                        : std::array<float, 4>{0.90f, 0.30f, 0.30f, 0.30f}));
        v->set_overlay(overlay);
        orca::viewport::CutView view;
        view.object = static_cast<int>(request.via.object);
        const auto& n = preview.normal;
        const auto& c = preview.center;
        view.plane = {float(n[0]), float(n[1]), float(n[2]), float(-(n[0] * c[0] + n[1] * c[1] + n[2] * c[2]))};
        const bool connectors = request.mode == orca::engine::CutMode::Planar && !request.connectors.empty();
        view.keep_above = connectors || request.mode == orca::engine::CutMode::Dovetail || request.options.keep_upper;
        view.keep_below = connectors || request.mode == orca::engine::CutMode::Dovetail || request.options.keep_lower;
        view.hide_above = show_face == JNI_TRUE;
        v->set_cut_view(view);
        return to_jstring(env, json::to_json(preview));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

void native_cut_hide(JNIEnv*, jclass, jlong viewport_handle)
{
    if (auto* v = from_handle<Viewport>(viewport_handle)) {
        v->set_overlay(nullptr);
        v->set_cut_view(std::nullopt);
    }
}

// Where a screen point lands on the cut plane: {"u","v","inside"}, or {} when it misses.
jstring native_cut_point_at(JNIEnv* env, jclass, jlong viewport_handle, jlong project_handle, jstring request_json,
                            jfloat x, jfloat y)
{
    auto* v = from_handle<Viewport>(viewport_handle);
    auto* project = from_handle<Project>(project_handle);
    if (v == nullptr || project == nullptr)
        return to_jstring(env, "{}");
    try {
        const auto request = json::cut_request_from_json(to_std_string(env, request_json));
        const orca::engine::CutPreview preview = orca::engine::cut_preview(*project, request);
        const auto& c = preview.center;
        const auto& n = preview.normal;
        const auto hit = v->plane_point(x, y, {float(c[0]), float(c[1]), float(c[2])}, {float(n[0]), float(n[1]), float(n[2])});
        if (!hit)
            return to_jstring(env, "{}");
        const auto point = orca::engine::cut_plane_point(*project, request, {(*hit)[0], (*hit)[1], (*hit)[2]});
        return to_jstring(env, nlohmann::json{{"u", point.at[0]}, {"v", point.at[1]}, {"inside", point.inside_section}}.dump());
    } catch (const std::exception&) {
        return to_jstring(env, "{}");
    }
}

// How far (mm) a vertical drag between two screen points moves a point at
// (ax, ay, az) up or down; NaN when the camera looks straight down.
jfloat native_vertical_drag(JNIEnv*, jclass, jlong viewport_handle, jfloat ax, jfloat ay, jfloat az, jfloat x0, jfloat y0,
                            jfloat x1, jfloat y1)
{
    auto* v = from_handle<Viewport>(viewport_handle);
    if (v == nullptr)
        return std::numeric_limits<float>::quiet_NaN();
    const auto dz = v->vertical_drag({ax, ay, az}, x0, y0, x1, y1);
    return dz ? *dz : std::numeric_limits<float>::quiet_NaN();
}

#define STR "Ljava/lang/String;"
const JNINativeMethod kEngineMethods[] = {
    {"nativeCutPreview", "(J" STR ")" STR, reinterpret_cast<void*>(native_cut_preview)},
    {"nativeCutPerform", "(J" STR ")" STR, reinterpret_cast<void*>(native_cut_perform)},
};
const JNINativeMethod kViewportMethods[] = {
    {"nativeCutShow", "(JJ" STR "Z)" STR, reinterpret_cast<void*>(native_cut_show)},
    {"nativeCutHide", "(J)V", reinterpret_cast<void*>(native_cut_hide)},
    {"nativeCutPointAt", "(JJ" STR "FF)" STR, reinterpret_cast<void*>(native_cut_point_at)},
    {"nativeVerticalDrag", "(JFFFFFFF)F", reinterpret_cast<void*>(native_vertical_drag)},
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

bool register_mesh_natives(JNIEnv* env, jclass native_engine)
{
    return register_methods(env, native_engine, kEngineMethods, sizeof(kEngineMethods) / sizeof(kEngineMethods[0]));
}

bool register_mesh_view_natives(JNIEnv* env, jclass native_viewport)
{
    return register_methods(env, native_viewport, kViewportMethods, sizeof(kViewportMethods) / sizeof(kViewportMethods[0]));
}

} // namespace orca::jni
