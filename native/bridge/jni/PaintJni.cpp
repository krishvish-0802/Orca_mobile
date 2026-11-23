// Painting natives of com.orcamobile.core.viewport.jni.NativeViewport: a paint
// session on one object, brushed through the plate's viewport. The session
// lives behind a jlong handle until nativePaintEnd.
#include <jni.h>

#include <array>
#include <cmath>
#include <exception>
#include <memory>
#include <optional>
#include <vector>

#include "JniRegistry.hpp"
#include "JniUtils.hpp"
#include "orca_engine/Painting.hpp"
#include "orca_engine/Project.hpp"
#include "orca_viewport/Viewport.hpp"

namespace orca::jni {

namespace {

using orca::engine::PaintSession;
using orca::viewport::Viewport;

// A session and where the last brush step landed, to join steps into a stroke.
struct Painting {
    std::unique_ptr<PaintSession> session;
    struct Last {
        int instance = 0;
        int volume = 0;
        int facet = 0;
        std::array<float, 3> hit{};
    };
    std::optional<Last> last;
};

std::shared_ptr<const orca::viewport::PaintOverlayMesh> overlay_mesh(const PaintSession& session, std::size_t volume)
{
    orca::engine::PaintOverlay overlay = session.overlay(volume);
    auto mesh = std::make_shared<orca::viewport::PaintOverlayMesh>();
    mesh->positions = std::move(overlay.positions);
    for (const auto& run : overlay.runs)
        mesh->runs.push_back({run.state, run.first_triangle, run.triangle_count});
    return mesh;
}

void show_all(Viewport& v, const PaintSession& session)
{
    v.clear_paint_overlays();
    for (std::size_t volume : session.paintable_volumes())
        v.set_paint_overlay(static_cast<int>(session.object()), static_cast<int>(volume), overlay_mesh(session, volume));
}

// A session on `object` for paint `kind` (PaintKind), shown on the viewport; 0 on
// failure. `target` (or -1): the painted modifier a PaintedModifier session shapes.
jlong native_paint_begin(JNIEnv*, jclass, jlong viewport_handle, jlong project_handle, jint object, jint kind, jint target)
{
    auto* v = from_handle<Viewport>(viewport_handle);
    auto* project = from_handle<orca::engine::Project>(project_handle);
    if (v == nullptr || project == nullptr || object < 0 || kind < 0 || kind > 4)
        return 0;
    try {
        auto painting = std::make_unique<Painting>();
        const std::optional<std::size_t> modifier = target >= 0 ? std::optional<std::size_t>(std::size_t(target)) : std::nullopt;
        painting->session = std::make_unique<PaintSession>(*project, std::size_t(object),
                                                           static_cast<orca::engine::PaintKind>(kind), modifier);
        show_all(*v, *painting->session);
        return to_handle(painting.release());
    } catch (const std::exception&) {
        return 0;
    }
}

// One brush step at a screen point; `stroke_start` begins a new stroke. Returns
// whether it painted. tool: PaintTool; state: what the brush paints (0 erases).
jboolean native_paint_at(JNIEnv*, jclass, jlong viewport_handle, jlong painting_handle, jfloat x, jfloat y,
                         jboolean stroke_start, jint state, jint tool, jfloat radius_mm, jfloat fill_angle_deg,
                         jfloat overhang_angle_deg)
{
    auto* v = from_handle<Viewport>(viewport_handle);
    auto* painting = from_handle<Painting>(painting_handle);
    if (v == nullptr || painting == nullptr || tool < 0 || tool > 3)
        return JNI_FALSE;
    if (stroke_start)
        painting->last.reset();
    const auto hit = v->pick_surface(x, y);
    if (!hit || hit->part.object != static_cast<int>(painting->session->object())) {
        painting->last.reset();   // off the object: the stroke breaks here
        return JNI_FALSE;
    }
    orca::engine::PaintStroke stroke;
    stroke.via = {std::size_t(hit->part.object), std::size_t(hit->part.instance)};
    stroke.volume = std::size_t(hit->volume);
    stroke.facet = static_cast<int>(hit->triangle);
    stroke.hit = hit->hit;
    stroke.camera = hit->camera;
    stroke.state = state;
    stroke.tool = static_cast<orca::engine::PaintTool>(tool);
    stroke.radius_mm = radius_mm;
    stroke.fill_angle_deg = fill_angle_deg;
    stroke.overhang_angle_deg = overhang_angle_deg;
    const auto& last = painting->last;
    if (last && last->instance == hit->part.instance && last->volume == hit->volume) {
        stroke.previous = last->hit;
        stroke.previous_facet = last->facet;
    }
    try {
        if (!painting->session->paint(stroke))
            return JNI_FALSE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
    painting->last = Painting::Last{hit->part.instance, hit->volume, stroke.facet, hit->hit};
    v->set_paint_overlay(hit->part.object, hit->volume, overlay_mesh(*painting->session, stroke.volume));
    return JNI_TRUE;
}

// Erases everything the session's kind painted on the object.
void native_paint_clear(JNIEnv*, jclass, jlong viewport_handle, jlong painting_handle)
{
    auto* v = from_handle<Viewport>(viewport_handle);
    auto* painting = from_handle<Painting>(painting_handle);
    if (v == nullptr || painting == nullptr)
        return;
    painting->session->clear();
    show_all(*v, *painting->session);
}

// Writes the paint into the model; true when it changed what will be sliced.
jboolean native_paint_commit(JNIEnv*, jclass, jlong painting_handle)
{
    auto* painting = from_handle<Painting>(painting_handle);
    if (painting == nullptr)
        return JNI_FALSE;
    try {
        return painting->session->commit() ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
}

// The painted modifier a PaintedModifier session shapes, or -1 while there is none.
jint native_paint_target(JNIEnv*, jclass, jlong painting_handle)
{
    auto* painting = from_handle<Painting>(painting_handle);
    if (painting == nullptr)
        return -1;
    const auto target = painting->session->target();
    return target ? jint(*target) : -1;
}

// Closes the session and hides the paint.
void native_paint_end(JNIEnv*, jclass, jlong viewport_handle, jlong painting_handle)
{
    if (auto* v = from_handle<Viewport>(viewport_handle))
        v->clear_paint_overlays();
    delete from_handle<Painting>(painting_handle);
}

// Colours by painted state: r, g, b, a per state (0 first).
void native_set_paint_palette(JNIEnv* env, jclass, jlong viewport_handle, jfloatArray rgba)
{
    auto* v = from_handle<Viewport>(viewport_handle);
    if (v == nullptr)
        return;
    const std::vector<float> values = to_float_vector(env, rgba);
    std::vector<std::array<float, 4>> palette;
    for (std::size_t i = 0; i + 3 < values.size(); i += 4)
        palette.push_back({values[i], values[i + 1], values[i + 2], values[i + 3]});
    v->set_paint_palette(std::move(palette));
}

// Colours of the filaments, filament 1 first: r, g, b, a each. Empty for one filament.
void native_set_filament_colours(JNIEnv* env, jclass, jlong viewport_handle, jfloatArray rgba)
{
    auto* v = from_handle<Viewport>(viewport_handle);
    if (v == nullptr)
        return;
    const std::vector<float> values = to_float_vector(env, rgba);
    std::vector<std::array<float, 4>> colours;
    for (std::size_t i = 0; i + 3 < values.size(); i += 4)
        colours.push_back({values[i], values[i + 1], values[i + 2], values[i + 3]});
    v->set_filament_colours(std::move(colours));
}

const JNINativeMethod kMethods[] = {
    {"nativePaintBegin", "(JJIII)J", reinterpret_cast<void*>(native_paint_begin)},
    {"nativePaintTarget", "(J)I", reinterpret_cast<void*>(native_paint_target)},
    {"nativePaintAt", "(JJFFZIIFFF)Z", reinterpret_cast<void*>(native_paint_at)},
    {"nativePaintClear", "(JJ)V", reinterpret_cast<void*>(native_paint_clear)},
    {"nativePaintCommit", "(J)Z", reinterpret_cast<void*>(native_paint_commit)},
    {"nativePaintEnd", "(JJ)V", reinterpret_cast<void*>(native_paint_end)},
    {"nativeSetPaintPalette", "(J[F)V", reinterpret_cast<void*>(native_set_paint_palette)},
    {"nativeSetFilamentColours", "(J[F)V", reinterpret_cast<void*>(native_set_filament_colours)},
};

} // namespace

bool register_paint_natives(JNIEnv* env, jclass native_viewport)
{
    if (env->RegisterNatives(native_viewport, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) == 0)
        return true;
    env->ExceptionClear();
    return false;
}

} // namespace orca::jni
