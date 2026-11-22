// Mesh tool natives. On com.orcamobile.core.engine.jni.NativeEngine: the cut
// itself and its checks. On com.orcamobile.core.viewport.jni.NativeViewport: the
// cut shown on the plate (plane, section, connectors) and taps on its plane.
#include <jni.h>

#include <array>
#include <cmath>
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

// What simplifying would leave: {"before","after"}, or {"error"}.
jstring native_simplify_preview(JNIEnv* env, jclass, jlong project_handle, jint object, jstring request_json)
{
    auto* project = from_handle<Project>(project_handle);
    if (project == nullptr || object < 0)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        const auto request = json::simplify_request_from_json(std::size_t(object), to_std_string(env, request_json));
        orca::engine::SimplifyOutcome outcome;
        run_on_engine_thread([&] { outcome = orca::engine::simplify_preview(*project, request); });
        return to_jstring(env, json::to_json(outcome));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// Shows what simplifying would leave as a wireframe over the object; returns
// its counts as nativeSimplifyPreview.
jstring native_simplify_show(JNIEnv* env, jclass, jlong viewport_handle, jlong project_handle, jint object,
                             jstring request_json)
{
    auto* v = from_handle<Viewport>(viewport_handle);
    auto* project = from_handle<Project>(project_handle);
    if (v == nullptr || project == nullptr || object < 0)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        const auto request = json::simplify_request_from_json(std::size_t(object), to_std_string(env, request_json));
        orca::engine::SimplifyOutcome outcome = orca::engine::simplify_preview(*project, request);
        auto overlay = std::make_shared<orca::viewport::Overlay>();
        overlay->shapes.push_back(shape(std::move(outcome.wireframe), true, {0.10f, 0.10f, 0.12f, 1.0f}));
        overlay->shapes.back().line_width = 1.f;
        v->set_overlay(overlay);
        v->set_cut_view(std::nullopt);
        return to_jstring(env, json::to_json(outcome));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// --- Measure and assembly ---

jstring native_measure(JNIEnv* env, jclass, jlong project_handle, jstring request_json)
{
    auto* project = from_handle<Project>(project_handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        orca::engine::FeatureFilter filter = orca::engine::FeatureFilter::Any;
        const auto picks = json::feature_picks_from_json(to_std_string(env, request_json), filter);
        orca::engine::MeasureOutcome outcome;
        run_on_engine_thread([&] { outcome = orca::engine::measure(*project, picks, filter); });
        return to_jstring(env, json::to_json(outcome));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// One assembly step: {"placed":0,"objects":[...]}, or {"error"}.
jstring native_assemble(JNIEnv* env, jclass, jlong project_handle, jstring request_json)
{
    auto* project = from_handle<Project>(project_handle);
    if (project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        const auto r = json::assemble_request_from_json(to_std_string(env, request_json));
        run_on_engine_thread([&] { orca::engine::assemble(*project, r.first, r.second, r.filter, r.step, r.value, r.xyz); });
        return to_jstring(env, json::to_json(orca::engine::PlateChange{}, project->objects()));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
    }
}

// The part surface under a screen point as a pick: {"object","instance","volume","facet","hit"}, or {}.
jstring native_measure_pick(JNIEnv* env, jclass, jlong viewport_handle, jfloat x, jfloat y)
{
    auto* v = from_handle<Viewport>(viewport_handle);
    if (v == nullptr)
        return to_jstring(env, "{}");
    const auto hit = v->pick_surface(x, y);
    if (!hit)
        return to_jstring(env, "{}");
    return to_jstring(env, nlohmann::json{{"object", hit->part.object},
                                          {"instance", hit->part.instance},
                                          {"volume", hit->volume},
                                          {"facet", hit->triangle},
                                          {"hit", hit->hit}}
                               .dump());
}

using Vec = std::array<double, 3>;

Vec add(const Vec& a, const Vec& b, double s = 1) { return {a[0] + s * b[0], a[1] + s * b[1], a[2] + s * b[2]}; }

void push(std::vector<float>& out, const Vec& p)
{
    out.push_back(float(p[0]));
    out.push_back(float(p[1]));
    out.push_back(float(p[2]));
}

// A small diamond marking a point.
void marker(std::vector<float>& out, const Vec& at, double r)
{
    const Vec axes[6] = {{r, 0, 0}, {-r, 0, 0}, {0, r, 0}, {0, -r, 0}, {0, 0, r}, {0, 0, -r}};
    for (int x : {0, 1})
        for (int y : {2, 3})
            for (int z : {4, 5}) {
                push(out, add(at, axes[x]));
                push(out, add(at, axes[y]));
                push(out, add(at, axes[z]));
            }
}

Vec cross(const Vec& a, const Vec& b) { return {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }

Vec unit(const Vec& v)
{
    const double n = std::sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    return n < 1e-12 ? Vec{0, 0, 0} : Vec{v[0] / n, v[1] / n, v[2] / n};
}

// A circle as segments, about `normal`.
void circle(std::vector<float>& out, const Vec& center, const Vec& normal, double radius)
{
    const Vec n = unit(normal);
    if (n[0] == 0 && n[1] == 0 && n[2] == 0)
        return;
    const Vec u = unit(std::abs(n[0]) < 0.9 ? cross(n, {1, 0, 0}) : cross(n, {0, 1, 0}));
    const Vec w = cross(n, u);
    const int segments = 72;
    auto at = [&](int i) {
        const double t = 2 * M_PI * i / segments;
        return add(add(center, u, radius * std::cos(t)), w, radius * std::sin(t));
    };
    for (int i = 0; i < segments; ++i) {
        push(out, at(i));
        push(out, at(i + 1));
    }
}

void add_feature(orca::viewport::Overlay& overlay, const orca::engine::MeasuredFeature& f, std::array<float, 4> color, double marker_mm)
{
    using orca::engine::FeatureKind;
    std::vector<float> lines, triangles;
    switch (f.kind) {
    case FeatureKind::Point: marker(triangles, f.a, marker_mm); break;
    case FeatureKind::Edge:
        push(lines, f.a);
        push(lines, f.b);
        marker(triangles, f.a, marker_mm * 0.6);
        marker(triangles, f.b, marker_mm * 0.6);
        break;
    case FeatureKind::Circle:
        circle(lines, f.a, f.b, f.radius);
        marker(triangles, f.a, marker_mm * 0.6);
        break;
    case FeatureKind::Plane: {
        auto plane = shape(f.plane, false, {color[0], color[1], color[2], 0.65f});
        overlay.shapes.push_back(std::move(plane));
        break;
    }
    }
    if (!lines.empty()) {
        overlay.shapes.push_back(shape(std::move(lines), true, color, true));
        overlay.shapes.back().line_width = 5.f;
    }
    if (!triangles.empty())
        overlay.shapes.push_back(shape(std::move(triangles), false, color, true));
}

// Shows the picked features and the distance between them; returns the outcome as nativeMeasure.
jstring native_measure_show(JNIEnv* env, jclass, jlong viewport_handle, jlong project_handle, jstring request_json)
{
    auto* v = from_handle<Viewport>(viewport_handle);
    auto* project = from_handle<Project>(project_handle);
    if (v == nullptr || project == nullptr)
        return to_jstring(env, json::error_json("The project is no longer loaded"));
    try {
        orca::engine::FeatureFilter filter = orca::engine::FeatureFilter::Any;
        const auto picks = json::feature_picks_from_json(to_std_string(env, request_json), filter);
        const orca::engine::MeasureOutcome outcome = orca::engine::measure(*project, picks, filter);
        auto overlay = std::make_shared<orca::viewport::Overlay>();
        // The desktop's colours for the first and second feature.
        const std::array<float, 4> colors[2] = {{0.25f, 0.75f, 1.0f, 1.0f}, {1.0f, 0.30f, 0.30f, 1.0f}};
        for (std::size_t i = 0; i < outcome.features.size() && i < 2; ++i)
            add_feature(*overlay, outcome.features[i], colors[i], 0.8);
        if (outcome.measurement && outcome.measurement->distance) {
            std::vector<float> dimension;
            push(dimension, outcome.measurement->from);
            push(dimension, outcome.measurement->to);
            overlay->shapes.push_back(shape(std::move(dimension), true, {1.0f, 0.85f, 0.10f, 1.0f}, true));
        }
        v->set_overlay(overlay);
        v->set_cut_view(std::nullopt);
        return to_jstring(env, json::to_json(outcome));
    } catch (const std::exception& e) {
        return to_jstring(env, json::error_json(e.what()));
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
    {"nativeSimplifyPreview", "(JI" STR ")" STR, reinterpret_cast<void*>(native_simplify_preview)},
    {"nativeMeasure", "(J" STR ")" STR, reinterpret_cast<void*>(native_measure)},
    {"nativeAssemble", "(J" STR ")" STR, reinterpret_cast<void*>(native_assemble)},
};
const JNINativeMethod kViewportMethods[] = {
    {"nativeCutShow", "(JJ" STR "Z)" STR, reinterpret_cast<void*>(native_cut_show)},
    {"nativeCutHide", "(J)V", reinterpret_cast<void*>(native_cut_hide)},
    {"nativeCutPointAt", "(JJ" STR "FF)" STR, reinterpret_cast<void*>(native_cut_point_at)},
    {"nativeSimplifyShow", "(JJI" STR ")" STR, reinterpret_cast<void*>(native_simplify_show)},
    {"nativeMeasurePick", "(JFF)" STR, reinterpret_cast<void*>(native_measure_pick)},
    {"nativeMeasureShow", "(JJ" STR ")" STR, reinterpret_cast<void*>(native_measure_show)},
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
