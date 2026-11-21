// Natives of com.orcamobile.core.viewport.jni.NativeViewport. The viewport lives
// behind a jlong handle; GL calls arrive on the GLSurfaceView thread, the rest
// on any thread.
#include <jni.h>

#include "JniRegistry.hpp"
#include "JniUtils.hpp"
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

// Hands the project's triangles straight to the viewport, without a trip
// through Kotlin.
void native_show_project(JNIEnv*, jclass, jlong handle, jlong project_handle)
{
    auto* v = viewport(handle);
    auto* project = from_handle<orca::engine::Project>(project_handle);
    if (v == nullptr)
        return;
    if (project == nullptr) {
        v->clear_mesh();
        return;
    }
    orca::engine::RenderMesh source = project->render_mesh();
    orca::viewport::Mesh mesh;
    mesh.positions = std::move(source.positions);
    mesh.normals = std::move(source.normals);
    v->set_mesh(std::move(mesh));
}

void native_clear_mesh(JNIEnv*, jclass, jlong handle)
{
    if (auto* v = viewport(handle))
        v->clear_mesh();
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
