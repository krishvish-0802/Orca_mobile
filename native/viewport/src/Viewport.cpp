#include "orca_viewport/Viewport.hpp"

#include <GLES3/gl3.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <memory>
#include <limits>
#include <mutex>
#include <optional>
#include <string>

#include "BedGeometry.hpp"
#include "Camera.hpp"
#include "GlProgram.hpp"
#include "Shaders.hpp"

namespace orca::viewport {

namespace {

constexpr const char* kLogTag = "OrcaViewport";

// Vertex arrays for one batch of geometry: positions, optional normals.
class GpuBatch {
public:
    void upload(const std::vector<float>& positions, const std::vector<float>* normals)
    {
        release();
        m_count = static_cast<GLsizei>(positions.size() / 3);
        if (m_count == 0)
            return;
        glGenVertexArrays(1, &m_vao);
        glBindVertexArray(m_vao);
        m_buffers[0] = make_buffer(positions, 0);
        if (normals != nullptr && normals->size() == positions.size())
            m_buffers[1] = make_buffer(*normals, 1);
        glBindVertexArray(0);
    }

    void draw(GLenum mode) const
    {
        if (m_count == 0)
            return;
        glBindVertexArray(m_vao);
        glDrawArrays(mode, 0, m_count);
        glBindVertexArray(0);
    }

    void release()
    {
        if (m_vao != 0)
            glDeleteVertexArrays(1, &m_vao);
        for (GLuint& buffer : m_buffers)
            if (buffer != 0)
                glDeleteBuffers(1, &buffer);
        abandon();
    }

    // Forgets GL names after the context was lost (they died with it).
    void abandon()
    {
        m_vao = 0;
        m_buffers[0] = m_buffers[1] = 0;
        m_count = 0;
    }

private:
    static GLuint make_buffer(const std::vector<float>& data, GLuint attribute)
    {
        GLuint buffer = 0;
        glGenBuffers(1, &buffer);
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(data.size() * sizeof(float)), data.data(), GL_STATIC_DRAW);
        glEnableVertexAttribArray(attribute);
        glVertexAttribPointer(attribute, 3, GL_FLOAT, GL_FALSE, 0, nullptr);
        return buffer;
    }

    GLuint m_vao = 0;
    GLuint m_buffers[2] = {0, 0};
    GLsizei m_count = 0;
};

struct Color {
    float r, g, b, a;
};
constexpr Color kBackground{0.90f, 0.92f, 0.92f, 1.0f};
constexpr Color kPlate{0.78f, 0.80f, 0.80f, 1.0f};
constexpr Color kGrid{0.62f, 0.65f, 0.65f, 1.0f};
constexpr Color kOutline{0.25f, 0.28f, 0.28f, 1.0f};
constexpr Color kModel{0.00f, 0.55f, 0.55f, 1.0f};

} // namespace

struct Viewport::Impl {
    std::mutex mutex;
    Camera camera;

    // Scene waiting to be uploaded on the GL thread.
    std::optional<Bed> pending_bed;
    std::optional<Mesh> pending_mesh;
    bool clear_mesh = false;
    // CPU copy of the shown mesh, re-uploaded when Android recreates the GL context.
    std::shared_ptr<const Mesh> shown_mesh;
    // The pending mesh is that copy coming back after a context loss: keep the camera.
    bool restoring_mesh = false;

    // Bounds of what is shown, for fit().
    BedGeometry bed_geometry;
    bool has_bed = false;
    Eigen::Vector3f mesh_min{0, 0, 0};
    Eigen::Vector3f mesh_max{0, 0, 0};
    bool has_mesh = false;
    bool framed = false;

    // GL state, touched only on the GL thread.
    GlProgram lit_program;
    GlProgram line_program;
    GpuBatch plate, grid, outline, model;
    bool gl_ready = false;

    void frame_scene_locked()
    {
        if (has_mesh) {
            const Eigen::Vector3f center = (mesh_min + mesh_max) * 0.5f;
            camera.frame(center, (mesh_max - mesh_min).norm() * 0.5f);
        } else if (has_bed) {
            const Eigen::Vector3f center((bed_geometry.min_x + bed_geometry.max_x) * 0.5f,
                                         (bed_geometry.min_y + bed_geometry.max_y) * 0.5f, 0.0f);
            const float radius = 0.5f * std::hypot(bed_geometry.max_x - bed_geometry.min_x,
                                                   bed_geometry.max_y - bed_geometry.min_y);
            camera.frame(center, radius);
        }
        framed = has_mesh || has_bed;
    }
};

Viewport::Viewport() : m_impl(std::make_unique<Impl>()) {}
Viewport::~Viewport() = default;

void Viewport::on_surface_created()
{
    Impl& d = *m_impl;
    // A new context: earlier GL names belong to the lost one.
    d.lit_program.abandon();
    d.line_program.abandon();
    d.plate.abandon();
    d.grid.abandon();
    d.outline.abandon();
    d.model.abandon();

    std::string error;
    d.gl_ready = d.lit_program.build(shaders::kLitVertex, shaders::kLitFragment, error) &&
                 d.line_program.build(shaders::kLineVertex, shaders::kLineFragment, error);
    if (!d.gl_ready)
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Shader setup failed: %s", error.c_str());

    // Re-upload what was shown before the context was lost.
    std::lock_guard<std::mutex> lock(d.mutex);
    if (d.has_bed && !d.pending_bed) {
        // The geometry is kept; an empty Bed re-uploads it in draw().
        d.pending_bed.emplace();
    }
    if (d.shown_mesh && !d.pending_mesh) {
        d.pending_mesh = *d.shown_mesh;
        d.restoring_mesh = true;
    }
}

void Viewport::on_surface_changed(int width_px, int height_px)
{
    glViewport(0, 0, width_px, height_px);
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->camera.set_viewport(width_px, height_px);
}

void Viewport::draw()
{
    Impl& d = *m_impl;
    Camera camera;
    {
        std::lock_guard<std::mutex> lock(d.mutex);
        if (d.pending_bed) {
            if (!d.pending_bed->outline.empty()) {
                d.bed_geometry = build_bed_geometry(*d.pending_bed);
                d.has_bed = !d.bed_geometry.plate_positions.empty();
            }
            d.plate.upload(d.bed_geometry.plate_positions, &d.bed_geometry.plate_normals);
            d.grid.upload(d.bed_geometry.grid_lines, nullptr);
            d.outline.upload(d.bed_geometry.outline_lines, nullptr);
            d.pending_bed.reset();
        }
        if (d.clear_mesh) {
            d.model.release();
            d.has_mesh = false;
            d.shown_mesh.reset();
            d.clear_mesh = false;
        }
        if (d.pending_mesh) {
            d.model.upload(d.pending_mesh->positions, &d.pending_mesh->normals);
            const auto& p = d.pending_mesh->positions;
            constexpr float inf = std::numeric_limits<float>::infinity();
            d.mesh_min = {inf, inf, inf};
            d.mesh_max = {-inf, -inf, -inf};
            for (size_t i = 0; i + 2 < p.size(); i += 3)
                for (int c = 0; c < 3; ++c) {
                    d.mesh_min[c] = std::min(d.mesh_min[c], p[i + c]);
                    d.mesh_max[c] = std::max(d.mesh_max[c], p[i + c]);
                }
            d.has_mesh = !p.empty();
            d.shown_mesh = std::make_shared<const Mesh>(std::move(*d.pending_mesh));
            d.pending_mesh.reset();
            if (!d.restoring_mesh)
                d.frame_scene_locked();
            d.restoring_mesh = false;
        }
        if (!d.framed)
            d.frame_scene_locked();
        camera = d.camera;
    }

    glClearColor(kBackground.r, kBackground.g, kBackground.b, kBackground.a);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    if (!d.gl_ready)
        return;
    glEnable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);

    const Eigen::Matrix4f mvp = camera.projection() * camera.view();
    const Eigen::Vector3f eye = camera.eye();

    d.lit_program.use();
    glUniformMatrix4fv(d.lit_program.uniform("u_mvp"), 1, GL_FALSE, mvp.data());
    const GLint lit_color = d.lit_program.uniform("u_color");
    glUniform3f(d.lit_program.uniform("u_eye"), eye.x(), eye.y(), eye.z());
    glUniform4f(lit_color, kPlate.r, kPlate.g, kPlate.b, kPlate.a);
    d.plate.draw(GL_TRIANGLES);

    d.line_program.use();
    glUniformMatrix4fv(d.line_program.uniform("u_mvp"), 1, GL_FALSE, mvp.data());
    const GLint line_color = d.line_program.uniform("u_color");
    glUniform4f(line_color, kGrid.r, kGrid.g, kGrid.b, kGrid.a);
    d.grid.draw(GL_LINES);
    glUniform4f(line_color, kOutline.r, kOutline.g, kOutline.b, kOutline.a);
    d.outline.draw(GL_LINES);

    d.lit_program.use();
    glUniform4f(lit_color, kModel.r, kModel.g, kModel.b, kModel.a);
    d.model.draw(GL_TRIANGLES);
}

void Viewport::set_bed(Bed bed)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->pending_bed = std::move(bed);
    m_impl->framed = false;
}

void Viewport::set_mesh(Mesh mesh)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->pending_mesh = std::move(mesh);
    m_impl->clear_mesh = false;
    m_impl->restoring_mesh = false;
}

void Viewport::clear_mesh()
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->pending_mesh.reset();
    m_impl->clear_mesh = true;
}

void Viewport::orbit(float dx_px, float dy_px)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->camera.orbit(dx_px, dy_px);
}

void Viewport::pan(float dx_px, float dy_px)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->camera.pan(dx_px, dy_px);
}

void Viewport::zoom(float factor, float focus_x_px, float focus_y_px)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->camera.zoom(factor, focus_x_px, focus_y_px);
}

void Viewport::fit()
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->frame_scene_locked();
}

} // namespace orca::viewport
