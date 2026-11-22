// Natives of com.orcamobile.core.viewport.jni.NativeViewport. The viewport lives
// behind a jlong handle; GL calls arrive on the GLSurfaceView thread, the rest
// on any thread.
#include <jni.h>

#include <array>
#include <cstdint>
#include <exception>
#include <map>
#include <memory>
#include <optional>
#include <vector>

#include "JniRegistry.hpp"
#include "JniUtils.hpp"
#include "orca_engine/GCodePreview.hpp"
#include "orca_engine/Project.hpp"
#include "orca_viewport/Viewport.hpp"

namespace orca::jni {

namespace {

using orca::viewport::Viewport;

Viewport* viewport(jlong handle) { return from_handle<Viewport>(handle); }

jlong native_create(JNIEnv*, jclass) { return to_handle(new Viewport()); }

void native_destroy(JNIEnv*, jclass, jlong handle) { delete viewport(handle); }

void native_surface_created(JNIEnv*, jclass, jlong handle)
{
    if (auto* v = viewport(handle))
        v->on_surface_created();
}

void native_surface_changed(JNIEnv*, jclass, jlong handle, jint width, jint height)
{
    if (auto* v = viewport(handle))
        v->on_surface_changed(width, height);
}

void native_draw(JNIEnv*, jclass, jlong handle)
{
    if (auto* v = viewport(handle))
        v->draw();
}

// outline: x0, y0, x1, y1, ... in mm.
void native_set_bed(JNIEnv* env, jclass, jlong handle, jfloatArray outline, jfloat max_height)
{
    auto* v = viewport(handle);
    if (v == nullptr)
        return;
    const std::vector<float> xy = to_float_vector(env, outline);
    orca::viewport::Bed bed;
    for (size_t i = 0; i + 1 < xy.size(); i += 2)
        bed.outline.push_back({xy[i], xy[i + 1]});
    bed.max_height = max_height;
    v->set_bed(std::move(bed));
}

using orca::viewport::Part;
using orca::viewport::PartMesh;
using orca::viewport::PartRef;

std::array<float, 16> to_float_matrix(const orca::engine::Placement& m)
{
    std::array<float, 16> out{};
    for (int i = 0; i < 16; ++i)
        out[i] = static_cast<float>(m[i]);
    return out;
}

orca::engine::InstanceRef to_instance(const PartRef& ref)
{
    return {static_cast<std::size_t>(ref.object), static_cast<std::size_t>(ref.instance)};
}

// Hands the project's objects straight to the viewport, without a trip
// through Kotlin: one mesh per object, one part per instance.
void native_show_project(JNIEnv*, jclass, jlong handle, jlong project_handle)
{
    auto* v = viewport(handle);
    auto* project = from_handle<orca::engine::Project>(project_handle);
    if (v == nullptr)
        return;
    if (project == nullptr) {
        v->clear_parts();
        return;
    }
    std::map<std::size_t, std::shared_ptr<const PartMesh>> meshes;
    std::vector<Part> parts;
    for (const orca::engine::InstanceRef& ref : project->instances()) {
        auto& mesh = meshes[ref.object];
        if (!mesh) {
            orca::engine::ObjectMesh object = project->object_mesh(ref.object);
            PartMesh part;
            part.positions = std::move(object.positions);
            for (const auto& piece : object.pieces) {
                orca::viewport::MeshPiece shown{static_cast<int>(piece.volume),
                                                static_cast<orca::viewport::VolumeKind>(static_cast<int>(piece.kind)),
                                                piece.first_triangle, piece.triangle_count};
                for (int i = 0; i < 16; ++i)
                    shown.transform[i] = static_cast<float>(piece.transform[i]);
                part.pieces.push_back(shown);
            }
            mesh = std::make_shared<const PartMesh>(std::move(part));
        }
        parts.push_back({{static_cast<int>(ref.object), static_cast<int>(ref.instance)}, mesh,
                         to_float_matrix(project->placement(ref))});
    }
    v->set_parts(std::move(parts));
}

void native_clear_mesh(JNIEnv*, jclass, jlong handle)
{
    if (auto* v = viewport(handle))
        v->clear_parts();
}

// The instance under a screen point as (object << 32 | instance), or -1.
jlong native_pick(JNIEnv*, jclass, jlong handle, jfloat x, jfloat y)
{
    auto* v = viewport(handle);
    if (v == nullptr)
        return -1;
    const std::optional<PartRef> hit = v->pick(x, y);
    if (!hit)
        return -1;
    return (static_cast<jlong>(hit->object) << 32) | static_cast<jlong>(static_cast<uint32_t>(hit->instance));
}

// Highlights a volume of the selected object, or none for a negative index.
void native_highlight_volume(JNIEnv*, jclass, jlong handle, jint volume)
{
    if (auto* v = viewport(handle))
        v->set_highlighted_volume(volume < 0 ? std::nullopt : std::optional<int>(volume));
}

void native_set_selection(JNIEnv*, jclass, jlong handle, jint object, jint instance)
{
    if (auto* v = viewport(handle))
        v->set_selection(object < 0 ? std::nullopt : std::optional<PartRef>(PartRef{object, instance}));
}

// Slides the selected instance by the bed distance between two screen points.
jboolean native_move_selection(JNIEnv*, jclass, jlong handle, jlong project_handle, jfloat x0, jfloat y0, jfloat x1,
                               jfloat y1)
{
    auto* v = viewport(handle);
    auto* project = from_handle<orca::engine::Project>(project_handle);
    if (v == nullptr || project == nullptr)
        return JNI_FALSE;
    const std::optional<PartRef> selection = v->selection();
    const auto from = v->bed_point(x0, y0);
    const auto to = v->bed_point(x1, y1);
    if (!selection || !from || !to)
        return JNI_FALSE;
    try {
        const orca::engine::InstanceRef ref = to_instance(*selection);
        project->translate(ref, (*to)[0] - (*from)[0], (*to)[1] - (*from)[1]);
        v->set_part_transform(*selection, to_float_matrix(project->placement(ref)));
        return JNI_TRUE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
}

// Turns the selected instance about the vertical axis (counter-clockwise seen from above).
jboolean native_rotate_selection(JNIEnv*, jclass, jlong handle, jlong project_handle, jfloat radians)
{
    auto* v = viewport(handle);
    auto* project = from_handle<orca::engine::Project>(project_handle);
    if (v == nullptr || project == nullptr)
        return JNI_FALSE;
    const std::optional<PartRef> selection = v->selection();
    if (!selection)
        return JNI_FALSE;
    try {
        const orca::engine::InstanceRef ref = to_instance(*selection);
        project->rotate_z(ref, radians);
        v->set_part_transform(*selection, to_float_matrix(project->placement(ref)));
        return JNI_TRUE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
}

// Lays the instance under a screen point on the side tapped there. Returns the
// instance as native_pick does, or -1 when the point hits none.
jlong native_lay_on_face(JNIEnv*, jclass, jlong handle, jlong project_handle, jfloat x, jfloat y)
{
    auto* v = viewport(handle);
    auto* project = from_handle<orca::engine::Project>(project_handle);
    if (v == nullptr || project == nullptr)
        return -1;
    const auto hit = v->pick_face(x, y);
    if (!hit)
        return -1;
    try {
        const orca::engine::InstanceRef ref = to_instance(hit->part);
        project->lay_on_face(ref, {hit->normal[0], hit->normal[1], hit->normal[2]});
        v->set_part_transform(hit->part, to_float_matrix(project->placement(ref)));
        return (static_cast<jlong>(hit->part.object) << 32) | static_cast<jlong>(static_cast<uint32_t>(hit->part.instance));
    } catch (const std::exception&) {
        return -1;
    }
}

// The volume under a screen point as [object, instance, volume, kind], or null.
jintArray native_pick_volume(JNIEnv* env, jclass, jlong handle, jfloat x, jfloat y)
{
    auto* v = viewport(handle);
    if (v == nullptr)
        return nullptr;
    const auto hit = v->pick_volume(x, y);
    if (!hit)
        return nullptr;
    const jint values[4] = {hit->part.object, hit->part.instance, hit->volume, static_cast<jint>(hit->kind)};
    jintArray out = env->NewIntArray(4);
    if (out != nullptr)
        env->SetIntArrayRegion(out, 0, 4, values);
    return out;
}

// After a volume moved: its placement in the object, and every instance of the
// object (moving a part can lift them all back onto the bed).
void show_volume_change(Viewport& v, const orca::engine::Project& project, const PartRef& part, int volume)
{
    const auto matrix = project.volume_matrix(static_cast<std::size_t>(part.object), static_cast<std::size_t>(volume));
    std::array<float, 16> shown{};
    for (int i = 0; i < 16; ++i)
        shown[i] = static_cast<float>(matrix[i]);
    v.set_volume_transform(part.object, volume, shown);
    for (const orca::engine::InstanceRef& ref : project.instances())
        if (static_cast<int>(ref.object) == part.object)
            v.set_part_transform({part.object, static_cast<int>(ref.instance)}, to_float_matrix(project.placement(ref)));
}

// Runs `change` on the highlighted volume of the selected instance.
template<class Change>
jboolean change_volume(jlong handle, jlong project_handle, Change&& change)
{
    auto* v = viewport(handle);
    auto* project = from_handle<orca::engine::Project>(project_handle);
    if (v == nullptr || project == nullptr)
        return JNI_FALSE;
    const std::optional<PartRef> selection = v->selection();
    const std::optional<int> volume = v->highlighted_volume();
    if (!selection || !volume)
        return JNI_FALSE;
    try {
        if (!change(*v, *project, to_instance(*selection), static_cast<std::size_t>(*volume)))
            return JNI_FALSE;
        show_volume_change(*v, *project, *selection, *volume);
        return JNI_TRUE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
}

// Slides the highlighted volume by the bed distance between two screen points.
jboolean native_move_volume(JNIEnv*, jclass, jlong handle, jlong project_handle, jfloat x0, jfloat y0, jfloat x1, jfloat y1)
{
    return change_volume(handle, project_handle, [&](Viewport& v, orca::engine::Project& project,
                                                     const orca::engine::InstanceRef& ref, std::size_t volume) {
        const auto from = v.bed_point(x0, y0);
        const auto to = v.bed_point(x1, y1);
        if (!from || !to)
            return false;
        project.translate_volume(ref, volume, {(*to)[0] - (*from)[0], (*to)[1] - (*from)[1], 0.0});
        return true;
    });
}

// Raises or lowers the highlighted volume by a vertical drag between two screen points.
jboolean native_lift_volume(JNIEnv*, jclass, jlong handle, jlong project_handle, jfloat x0, jfloat y0, jfloat x1, jfloat y1)
{
    return change_volume(handle, project_handle, [&](Viewport& v, orca::engine::Project& project,
                                                     const orca::engine::InstanceRef& ref, std::size_t volume) {
        const auto center = project.volume_center(ref, volume);
        const auto dz = v.vertical_drag({float(center[0]), float(center[1]), float(center[2])}, x0, y0, x1, y1);
        if (!dz)
            return false;
        project.translate_volume(ref, volume, {0.0, 0.0, *dz});
        return true;
    });
}

// Turns the highlighted volume about the vertical (counter-clockwise seen from above).
jboolean native_rotate_volume(JNIEnv*, jclass, jlong handle, jlong project_handle, jfloat radians)
{
    return change_volume(handle, project_handle, [&](Viewport&, orca::engine::Project& project,
                                                     const orca::engine::InstanceRef& ref, std::size_t volume) {
        project.rotate_volume_z(ref, volume, radians);
        return true;
    });
}

// Shows a loaded G-code preview's toolpaths; they stay shared with the preview.
void native_show_preview(JNIEnv*, jclass, jlong handle, jlong preview_handle)
{
    auto* v = viewport(handle);
    if (v == nullptr)
        return;
    if (auto* preview = from_handle<orca::engine::GCodePreview>(preview_handle))
        v->set_toolpaths(preview->toolpaths());
    else
        v->clear_toolpaths();
}

void native_clear_toolpaths(JNIEnv*, jclass, jlong handle)
{
    if (auto* v = viewport(handle))
        v->clear_toolpaths();
}

void native_set_layer_range(JNIEnv*, jclass, jlong handle, jint first, jint last)
{
    if (auto* v = viewport(handle); v != nullptr && first >= 0 && last >= first)
        v->set_layer_range(static_cast<std::size_t>(first), static_cast<std::size_t>(last));
}

void native_set_coloring(JNIEnv*, jclass, jlong handle, jint coloring)
{
    using orca::viewport::ToolpathColoring;
    auto* v = viewport(handle);
    if (v == nullptr || coloring < static_cast<jint>(ToolpathColoring::FeatureType) ||
        coloring > static_cast<jint>(ToolpathColoring::Filament))
        return;
    v->set_toolpath_coloring(static_cast<ToolpathColoring>(coloring));
}

void native_orbit(JNIEnv*, jclass, jlong handle, jfloat dx, jfloat dy)
{
    if (auto* v = viewport(handle))
        v->orbit(dx, dy);
}

void native_pan(JNIEnv*, jclass, jlong handle, jfloat dx, jfloat dy)
{
    if (auto* v = viewport(handle))
        v->pan(dx, dy);
}

void native_zoom(JNIEnv*, jclass, jlong handle, jfloat factor, jfloat x, jfloat y)
{
    if (auto* v = viewport(handle))
        v->zoom(factor, x, y);
}

void native_fit(JNIEnv*, jclass, jlong handle)
{
    if (auto* v = viewport(handle))
        v->fit();
}

const JNINativeMethod kMethods[] = {
    {"nativeCreate", "()J", reinterpret_cast<void*>(native_create)},
    {"nativeDestroy", "(J)V", reinterpret_cast<void*>(native_destroy)},
    {"nativeSurfaceCreated", "(J)V", reinterpret_cast<void*>(native_surface_created)},
    {"nativeSurfaceChanged", "(JII)V", reinterpret_cast<void*>(native_surface_changed)},
    {"nativeDraw", "(J)V", reinterpret_cast<void*>(native_draw)},
    {"nativeSetBed", "(J[FF)V", reinterpret_cast<void*>(native_set_bed)},
    {"nativeShowProject", "(JJ)V", reinterpret_cast<void*>(native_show_project)},
    {"nativeClearMesh", "(J)V", reinterpret_cast<void*>(native_clear_mesh)},
    {"nativePick", "(JFF)J", reinterpret_cast<void*>(native_pick)},
    {"nativeSetSelection", "(JII)V", reinterpret_cast<void*>(native_set_selection)},
    {"nativeMoveSelection", "(JJFFFF)Z", reinterpret_cast<void*>(native_move_selection)},
    {"nativeRotateSelection", "(JJF)Z", reinterpret_cast<void*>(native_rotate_selection)},
    {"nativeLayOnFace", "(JJFF)J", reinterpret_cast<void*>(native_lay_on_face)},
    {"nativeHighlightVolume", "(JI)V", reinterpret_cast<void*>(native_highlight_volume)},
    {"nativePickVolume", "(JFF)[I", reinterpret_cast<void*>(native_pick_volume)},
    {"nativeMoveVolume", "(JJFFFF)Z", reinterpret_cast<void*>(native_move_volume)},
    {"nativeLiftVolume", "(JJFFFF)Z", reinterpret_cast<void*>(native_lift_volume)},
    {"nativeRotateVolume", "(JJF)Z", reinterpret_cast<void*>(native_rotate_volume)},
    {"nativeShowPreview", "(JJ)V", reinterpret_cast<void*>(native_show_preview)},
    {"nativeClearToolpaths", "(J)V", reinterpret_cast<void*>(native_clear_toolpaths)},
    {"nativeSetLayerRange", "(JII)V", reinterpret_cast<void*>(native_set_layer_range)},
    {"nativeSetColoring", "(JI)V", reinterpret_cast<void*>(native_set_coloring)},
    {"nativeOrbit", "(JFF)V", reinterpret_cast<void*>(native_orbit)},
    {"nativePan", "(JFF)V", reinterpret_cast<void*>(native_pan)},
    {"nativeZoom", "(JFFF)V", reinterpret_cast<void*>(native_zoom)},
    {"nativeFit", "(J)V", reinterpret_cast<void*>(native_fit)},
};

} // namespace

bool register_viewport_natives(JNIEnv* env, jclass native_viewport)
{
    if (env->RegisterNatives(native_viewport, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) == 0)
        return true;
    env->ExceptionClear();
    return false;
}

} // namespace orca::jni
