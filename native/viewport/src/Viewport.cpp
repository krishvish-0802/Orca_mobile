#include "orca_viewport/Viewport.hpp"

#include <GLES3/gl3.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <memory>
#include <limits>
#include <map>
#include <mutex>
#include <optional>
#include <string>

#include "BedGeometry.hpp"
#include "Camera.hpp"
#include "GlProgram.hpp"
#include "Picking.hpp"
#include "Shaders.hpp"
#include "ToolpathRenderer.hpp"

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

    // Vertices first .. first + count.
    void draw(GLenum mode, GLint first, GLsizei count) const
    {
        if (m_count == 0 || count <= 0 || first >= m_count)
            return;
        glBindVertexArray(m_vao);
        glDrawArrays(mode, first, std::min(count, m_count - first));
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
constexpr Color kFloor{0.80f, 0.83f, 0.83f, 1.0f};    // faint: just a sense of ground
constexpr Color kModel{0.00f, 0.55f, 0.55f, 1.0f};
constexpr Color kSelected{0.95f, 0.55f, 0.10f, 1.0f};
// Volumes that are not printed themselves, drawn see-through (desktop colors, roughly).
constexpr Color kModifier{0.95f, 0.80f, 0.20f, 0.45f};
constexpr Color kNegative{0.25f, 0.25f, 0.28f, 0.50f};
constexpr Color kEnforcer{0.20f, 0.80f, 0.30f, 0.45f};
constexpr Color kBlocker{0.90f, 0.25f, 0.25f, 0.45f};
constexpr Color kSeam{0.65f, 0.35f, 0.90f, 0.45f};
constexpr Color kHighlightedVolume{0.30f, 0.60f, 1.00f, 0.75f};
constexpr Color kDiscarded{0.62f, 0.64f, 0.66f, 1.0f};

const Color& volume_color(VolumeKind kind)
{
    switch (kind) {
    case VolumeKind::ParameterModifier: return kModifier;
    case VolumeKind::NegativeVolume: return kNegative;
    case VolumeKind::SupportEnforcer: return kEnforcer;
    case VolumeKind::SupportBlocker: return kBlocker;
    case VolumeKind::SeamCenter:
    case VolumeKind::SeamLeft:
    case VolumeKind::SeamRight:
    case VolumeKind::SeamEnforced:
    case VolumeKind::SeamBlocked:
    case VolumeKind::SeamNeutral: return kSeam;
    case VolumeKind::PaintedModifier: return kModifier;
    case VolumeKind::ModelPart: break;
    }
    return kModel;
}

Eigen::Matrix4f to_matrix(const std::array<float, 16>& m)
{
    return Eigen::Map<const Eigen::Matrix4f>(m.data());   // column-major, like Eigen
}

// One volume of a shown instance: its triangles, where it sits in the object,
// and their bounds in its own coordinates.
struct PieceState {
    MeshPiece piece;
    Eigen::Matrix4f transform;   // volume to object
    Eigen::AlignedBox3f box;
};

// A part with what the viewport derives from it.
struct PartState {
    Part part;
    Eigen::Matrix4f transform;   // object to world
    std::vector<PieceState> pieces;
};

// An instance's volumes; a mesh without pieces is one model part.
std::vector<PieceState> pieces_of(const PartMesh& mesh)
{
    std::vector<MeshPiece> pieces = mesh.pieces;
    if (pieces.empty())
        pieces.push_back({0, VolumeKind::ModelPart, 0, mesh.positions.size() / 9});
    std::vector<PieceState> states;
    states.reserve(pieces.size());
    for (const MeshPiece& piece : pieces)
        states.push_back({piece, to_matrix(piece.transform), bounds_of(mesh, piece.first_triangle, piece.triangle_count)});
    return states;
}

} // namespace

struct Viewport::Impl {
    mutable std::mutex mutex;
    Camera camera;

    // --- scene, guarded by `mutex` ---
    std::optional<Bed> pending_bed;
    BedGeometry bed_geometry;
    bool has_bed = false;
    std::vector<PartState> parts;
    std::optional<PartRef> selection;
    std::optional<int> highlighted_volume;
    bool framed = false;
    std::map<std::pair<int, int>, std::shared_ptr<const PaintOverlayMesh>> paint_overlays;
    std::vector<std::array<float, 4>> paint_palette;
    std::shared_ptr<const Overlay> overlay;
    std::optional<CutView> cut_view;

    // Toolpaths: the shown data (kept for context loss) and whether it needs uploading.
    std::shared_ptr<const libvgcode::GCodeInputData> toolpaths;
    bool toolpaths_dirty = false;
    bool toolpaths_restoring = false;
    std::array<std::size_t, 2> layer_range{0, static_cast<std::size_t>(-1)};
    ToolpathColoring coloring = ToolpathColoring::FeatureType;
    bool toolpath_view_dirty = false;
    bool has_toolpaths = false;
    Eigen::Vector3f toolpaths_min{0, 0, 0};
    Eigen::Vector3f toolpaths_max{0, 0, 0};

    // --- GL state, touched only on the GL thread ---
    GlProgram lit_program;
    GlProgram part_program;
    GlProgram line_program;
    GpuBatch plate, grid, outline, floor;
    // One vertex array per part mesh, shared by the instances that use it.
    std::map<const PartMesh*, GpuBatch> part_batches;
    std::map<const PaintOverlayMesh*, GpuBatch> paint_batches;
    // The overlay shown and its vertex arrays, one per shape.
    const Overlay* overlay_uploaded = nullptr;
    std::vector<GpuBatch> overlay_batches;
    ToolpathRenderer toolpath_renderer;
    bool gl_ready = false;

    Eigen::AlignedBox3f parts_box_locked() const
    {
        Eigen::AlignedBox3f box;
        for (const PartState& state : parts)
            for (const PieceState& piece : state.pieces) {
                if (piece.box.isEmpty())
                    continue;
                const Eigen::Matrix4f placed = state.transform * piece.transform;
                for (int corner = 0; corner < 8; ++corner) {
                    const Eigen::Vector3f c = piece.box.corner(static_cast<Eigen::AlignedBox3f::CornerType>(corner));
                    box.extend((placed * c.homogeneous()).head<3>());
                }
            }
        return box;
    }

    void frame_scene_locked()
    {
        const Eigen::AlignedBox3f parts_box = parts_box_locked();
        if (has_toolpaths) {
            camera.frame((toolpaths_min + toolpaths_max) * 0.5f, (toolpaths_max - toolpaths_min).norm() * 0.5f);
        } else if (!parts_box.isEmpty()) {
            camera.frame(parts_box.center(), parts_box.diagonal().norm() * 0.5f);
        } else if (has_bed) {
            const Eigen::Vector3f center((bed_geometry.min_x + bed_geometry.max_x) * 0.5f,
                                         (bed_geometry.min_y + bed_geometry.max_y) * 0.5f, 0.0f);
            const float radius = 0.5f * std::hypot(bed_geometry.max_x - bed_geometry.min_x,
                                                   bed_geometry.max_y - bed_geometry.min_y);
            camera.frame(center, radius);
        }
        framed = has_toolpaths || !parts_box.isEmpty() || has_bed;
    }

    // GL thread: vertex arrays for exactly the meshes the parts use.
    void sync_paint_batches(const std::map<std::pair<int, int>, std::shared_ptr<const PaintOverlayMesh>>& shown)
    {
        std::map<const PaintOverlayMesh*, GpuBatch> next;
        for (const auto& [key, overlay] : shown) {
            const PaintOverlayMesh* mesh = overlay.get();
            if (mesh == nullptr || next.count(mesh) != 0)
                continue;
            auto existing = paint_batches.find(mesh);
            if (existing != paint_batches.end()) {
                next.emplace(mesh, existing->second);
                existing->second.abandon();
            } else {
                next[mesh].upload(mesh->positions, nullptr);
            }
        }
        for (auto& [mesh, batch] : paint_batches)
            batch.release();
        paint_batches = std::move(next);
    }

    // GL thread: opaque shapes first, then see-through ones, then those on top.
    void draw_overlay(const Overlay& shown, const Eigen::Matrix4f& view_proj, const Eigen::Vector3f& eye)
    {
        const Eigen::Matrix4f identity = Eigen::Matrix4f::Identity();
        for (int pass = 0; pass < 3; ++pass) {
            for (std::size_t i = 0; i < shown.shapes.size() && i < overlay_batches.size(); ++i) {
                const Overlay::Shape& shape = shown.shapes[i];
                const bool see_through = shape.color[3] < 1.0f;
                const int shape_pass = shape.on_top ? 2 : see_through ? 1 : 0;
                if (shape_pass != pass)
                    continue;
                if (see_through) {
                    glEnable(GL_BLEND);
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                    glDepthMask(GL_FALSE);
                }
                if (shape.on_top)
                    glDisable(GL_DEPTH_TEST);
                if (shape.lines) {
                    line_program.use();
                    glUniformMatrix4fv(line_program.uniform("u_mvp"), 1, GL_FALSE, view_proj.data());
                    glUniform4f(line_program.uniform("u_color"), shape.color[0], shape.color[1], shape.color[2], shape.color[3]);
                    glLineWidth(3.0f);
                    overlay_batches[i].draw(GL_LINES);
                    glLineWidth(1.0f);
                } else {
                    part_program.use();
                    glUniformMatrix4fv(part_program.uniform("u_view_proj"), 1, GL_FALSE, view_proj.data());
                    glUniformMatrix4fv(part_program.uniform("u_model"), 1, GL_FALSE, identity.data());
                    glUniform3f(part_program.uniform("u_eye"), eye.x(), eye.y(), eye.z());
                    glUniform4f(part_program.uniform("u_plane"), 0.0f, 0.0f, 0.0f, 0.0f);
                    glUniform1f(part_program.uniform("u_clip_above"), 0.0f);
                    glUniform4f(part_program.uniform("u_color"), shape.color[0], shape.color[1], shape.color[2], shape.color[3]);
                    // Lies on the model's surfaces (a section on a cut face): pulled toward the camera.
                    glEnable(GL_POLYGON_OFFSET_FILL);
                    glPolygonOffset(-1.0f, -2.0f);
                    overlay_batches[i].draw(GL_TRIANGLES);
                    glDisable(GL_POLYGON_OFFSET_FILL);
                }
                if (shape.on_top)
                    glEnable(GL_DEPTH_TEST);
                if (see_through) {
                    glDepthMask(GL_TRUE);
                    glDisable(GL_BLEND);
                }
            }
        }
    }

    void sync_overlay(const std::shared_ptr<const Overlay>& shown)
    {
        if (shown.get() == overlay_uploaded && overlay_batches.size() == (shown ? shown->shapes.size() : 0))
            return;
        for (GpuBatch& batch : overlay_batches)
            batch.release();
        overlay_batches.clear();
        overlay_uploaded = shown.get();
        if (!shown)
            return;
        overlay_batches.resize(shown->shapes.size());
        for (std::size_t i = 0; i < shown->shapes.size(); ++i)
            overlay_batches[i].upload(shown->shapes[i].positions, nullptr);
    }

    void sync_part_batches(const std::vector<PartState>& shown)
    {
        std::map<const PartMesh*, GpuBatch> next;
        for (const PartState& state : shown) {
            const PartMesh* mesh = state.part.mesh.get();
            if (mesh == nullptr || next.count(mesh) != 0)
                continue;
            auto existing = part_batches.find(mesh);
            if (existing != part_batches.end()) {
                next.emplace(mesh, existing->second);
                existing->second.abandon();   // ownership moved to `next`
            } else {
                next[mesh].upload(mesh->positions, nullptr);
            }
        }
        for (auto& [mesh, batch] : part_batches)
            batch.release();
        part_batches = std::move(next);
    }
};

Viewport::Viewport() : m_impl(std::make_unique<Impl>()) {}
Viewport::~Viewport() = default;

void Viewport::on_surface_created()
{
    Impl& d = *m_impl;
    // A new context: earlier GL names belong to the lost one. The toolpath
    // renderer goes first, while nothing of ours exists in the new context yet.
    d.toolpath_renderer.on_context_created();
    d.lit_program.abandon();
    d.part_program.abandon();
    d.line_program.abandon();
    d.plate.abandon();
    d.grid.abandon();
    d.outline.abandon();
    d.floor.abandon();
    for (auto& [mesh, batch] : d.part_batches)
        batch.abandon();
    d.part_batches.clear();   // re-uploaded at the next frame
    for (auto& [mesh, batch] : d.paint_batches)
        batch.abandon();
    d.paint_batches.clear();
    for (GpuBatch& batch : d.overlay_batches)
        batch.abandon();
    d.overlay_batches.clear();
    d.overlay_uploaded = nullptr;

    std::string error;
    d.gl_ready = d.lit_program.build(shaders::kLitVertex, shaders::kLitFragment, error) &&
                 d.part_program.build(shaders::kPartVertex, shaders::kPartFragment, error) &&
                 d.line_program.build(shaders::kLineVertex, shaders::kLineFragment, error);
    if (!d.gl_ready)
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Shader setup failed: %s", error.c_str());

    std::lock_guard<std::mutex> lock(d.mutex);
    if (d.has_bed && !d.pending_bed) {
        // The geometry is kept; an empty Bed re-uploads it in draw().
        d.pending_bed.emplace();
    }
    if (d.toolpaths) {
        d.toolpaths_restoring = !d.toolpaths_dirty;
        d.toolpaths_dirty = true;
    }
    d.has_toolpaths = false;
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
    std::vector<PartState> parts;
    std::optional<PartRef> selection;
    std::optional<int> highlighted_volume;
    std::map<std::pair<int, int>, std::shared_ptr<const PaintOverlayMesh>> paint_overlays;
    std::vector<std::array<float, 4>> paint_palette;
    std::shared_ptr<const Overlay> overlay;
    std::optional<CutView> cut_view;
    bool show_toolpaths = false;
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
            d.floor.upload(d.bed_geometry.floor_lines, nullptr);
            d.pending_bed.reset();
        }
        if (d.toolpaths_dirty) {
            d.toolpaths_dirty = false;
            d.has_toolpaths = false;
            if (d.toolpaths && d.gl_ready && d.toolpath_renderer.load(*d.toolpaths)) {
                const auto bounds = d.toolpath_renderer.bounds();
                d.toolpaths_min = bounds[0];
                d.toolpaths_max = bounds[1];
                d.has_toolpaths = true;
                d.toolpath_view_dirty = true;
            } else {
                d.toolpath_renderer.release();
            }
            if (!d.toolpaths_restoring)
                d.frame_scene_locked();
            d.toolpaths_restoring = false;
        }
        if (d.toolpath_view_dirty && d.has_toolpaths) {
            d.toolpath_renderer.set_coloring(d.coloring);
            d.toolpath_renderer.set_layer_range(d.layer_range[0], d.layer_range[1]);
            d.toolpath_view_dirty = false;
        }
        if (!d.framed)
            d.frame_scene_locked();
        camera = d.camera;
        parts = d.parts;
        selection = d.selection;
        highlighted_volume = d.highlighted_volume;
        paint_overlays = d.paint_overlays;
        paint_palette = d.paint_palette;
        overlay = d.overlay;
        cut_view = d.cut_view;
        show_toolpaths = d.has_toolpaths;
    }

    glClearColor(kBackground.r, kBackground.g, kBackground.b, kBackground.a);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    if (!d.gl_ready)
        return;
    d.sync_part_batches(parts);
    d.sync_paint_batches(paint_overlays);
    d.sync_overlay(overlay);
    glEnable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);

    const Eigen::Matrix4f view = camera.view();
    const Eigen::Matrix4f projection = camera.projection();
    const Eigen::Matrix4f view_proj = projection * view;
    const Eigen::Vector3f eye = camera.eye();

    // From under the bed the plate and its grid are hidden, so the first layer
    // and the model's underside show; the outline and the floor stay for bearings.
    const bool below_bed = eye.z() < 0.0f;
    if (!below_bed) {
        d.lit_program.use();
        glUniformMatrix4fv(d.lit_program.uniform("u_mvp"), 1, GL_FALSE, view_proj.data());
        glUniform3f(d.lit_program.uniform("u_eye"), eye.x(), eye.y(), eye.z());
        glUniform4f(d.lit_program.uniform("u_color"), kPlate.r, kPlate.g, kPlate.b, kPlate.a);
        d.plate.draw(GL_TRIANGLES);
    }

    d.line_program.use();
    glUniformMatrix4fv(d.line_program.uniform("u_mvp"), 1, GL_FALSE, view_proj.data());
    const GLint line_color = d.line_program.uniform("u_color");
    glUniform4f(line_color, kFloor.r, kFloor.g, kFloor.b, kFloor.a);
    d.floor.draw(GL_LINES);
    if (!below_bed) {
        glUniform4f(line_color, kGrid.r, kGrid.g, kGrid.b, kGrid.a);
        d.grid.draw(GL_LINES);
    }
    glUniform4f(line_color, kOutline.r, kOutline.g, kOutline.b, kOutline.a);
    d.outline.draw(GL_LINES);

    if (show_toolpaths) {
        d.toolpath_renderer.render(view, projection);
        return;
    }
    d.part_program.use();
    glUniformMatrix4fv(d.part_program.uniform("u_view_proj"), 1, GL_FALSE, view_proj.data());
    glUniform3f(d.part_program.uniform("u_eye"), eye.x(), eye.y(), eye.z());
    const GLint model_matrix = d.part_program.uniform("u_model");
    const GLint part_color = d.part_program.uniform("u_color");
    const GLint cut_plane = d.part_program.uniform("u_plane");
    const GLint color_above = d.part_program.uniform("u_color_above");
    const GLint clip_above = d.part_program.uniform("u_clip_above");
    glUniform4f(cut_plane, 0.0f, 0.0f, 0.0f, 0.0f);
    glUniform1f(clip_above, 0.0f);
    // Opaque model parts first, then the see-through volumes over them.
    for (int pass = 0; pass < 2; ++pass) {
        const bool see_through = pass == 1;
        if (see_through) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(GL_FALSE);
        }
        for (const PartState& state : parts) {
            auto batch = d.part_batches.find(state.part.mesh.get());
            if (batch == d.part_batches.end())
                continue;
            const bool selected = selection && *selection == state.part.ref;
            for (const PieceState& piece_state : state.pieces) {
                const MeshPiece& piece = piece_state.piece;
                if ((piece.kind != VolumeKind::ModelPart) != see_through)
                    continue;
                const Eigen::Matrix4f placed = state.transform * piece_state.transform;
                glUniformMatrix4fv(model_matrix, 1, GL_FALSE, placed.data());
                Color c = piece.kind == VolumeKind::ModelPart ? (selected ? kSelected : kModel) : volume_color(piece.kind);
                if (selected && highlighted_volume && *highlighted_volume == piece.volume)
                    c = piece.kind == VolumeKind::ModelPart ? Color{0.30f, 0.60f, 1.00f, 1.0f} : kHighlightedVolume;
                const bool cut = cut_view && cut_view->object == state.part.ref.object && piece.kind == VolumeKind::ModelPart;
                if (cut) {
                    // Above the plane: the selection's color; below: blue. A side not kept is grey.
                    const Color below = cut_view->keep_below ? Color{0.30f, 0.60f, 1.00f, 1.0f} : kDiscarded;
                    const Color above = cut_view->keep_above ? kSelected : kDiscarded;
                    c = below;
                    glUniform4f(cut_plane, cut_view->plane[0], cut_view->plane[1], cut_view->plane[2], cut_view->plane[3]);
                    glUniform4f(color_above, above.r, above.g, above.b, above.a);
                    glUniform1f(clip_above, cut_view->hide_above ? 1.0f : 0.0f);
                }
                glUniform4f(part_color, c.r, c.g, c.b, c.a);
                batch->second.draw(GL_TRIANGLES, static_cast<GLint>(piece.first_triangle * 3),
                                   static_cast<GLsizei>(piece.triangle_count * 3));
                if (cut) {
                    glUniform4f(cut_plane, 0.0f, 0.0f, 0.0f, 0.0f);
                    glUniform1f(clip_above, 0.0f);
                }
            }
        }
        if (see_through) {
            glDepthMask(GL_TRUE);
            glDisable(GL_BLEND);
        } else if (!paint_overlays.empty()) {
            // Paint lies on the parts: pulled toward the camera so it wins the depth test.
            glEnable(GL_POLYGON_OFFSET_FILL);
            glPolygonOffset(-1.0f, -2.0f);
            for (const PartState& state : parts)
                for (const PieceState& piece : state.pieces) {
                    auto overlay = paint_overlays.find({state.part.ref.object, piece.piece.volume});
                    if (overlay == paint_overlays.end() || !overlay->second)
                        continue;
                    auto batch = d.paint_batches.find(overlay->second.get());
                    if (batch == d.paint_batches.end())
                        continue;
                    const Eigen::Matrix4f placed = state.transform * piece.transform;
                    glUniformMatrix4fv(model_matrix, 1, GL_FALSE, placed.data());
                    for (const PaintOverlayMesh::Run& run : overlay->second->runs) {
                        std::array<float, 4> c{0.2f, 0.8f, 0.3f, 1.0f};
                        if (!paint_palette.empty())
                            c = paint_palette[std::min<std::size_t>(static_cast<std::size_t>(std::max(run.state, 0)), paint_palette.size() - 1)];
                        glUniform4f(part_color, c[0], c[1], c[2], 1.0f);
                        batch->second.draw(GL_TRIANGLES, static_cast<GLint>(run.first_triangle * 3),
                                           static_cast<GLsizei>(run.triangle_count * 3));
                    }
                }
            glDisable(GL_POLYGON_OFFSET_FILL);
        }
    }
    if (overlay)
        d.draw_overlay(*overlay, view_proj, eye);
}

void Viewport::set_paint_overlay(int object, int volume, std::shared_ptr<const PaintOverlayMesh> overlay)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    if (overlay)
        m_impl->paint_overlays[{object, volume}] = std::move(overlay);
    else
        m_impl->paint_overlays.erase({object, volume});
}

void Viewport::clear_paint_overlays()
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->paint_overlays.clear();
}

void Viewport::set_paint_palette(std::vector<std::array<float, 4>> palette)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->paint_palette = std::move(palette);
}

std::optional<Viewport::SurfaceHit> Viewport::pick_surface(float x_px, float y_px) const
{
    std::vector<PartState> parts;
    Eigen::Vector3f origin, direction, eye;
    {
        std::lock_guard<std::mutex> lock(m_impl->mutex);
        if (m_impl->has_toolpaths)
            return std::nullopt;
        m_impl->camera.ray(x_px, y_px, origin, direction);
        eye = m_impl->camera.eye();
        parts = m_impl->parts;
    }
    std::optional<SurfaceHit> best;
    float nearest = std::numeric_limits<float>::infinity();
    for (const PartState& state : parts)
        for (const PieceState& piece : state.pieces) {
            if (piece.piece.kind != VolumeKind::ModelPart)
                continue;
            const Eigen::Matrix4f placed = state.transform * piece.transform;
            std::size_t triangle = 0;
            const std::optional<float> t = intersect(*state.part.mesh, piece.piece.first_triangle, piece.piece.triangle_count,
                                                     piece.box, placed, origin, direction, &triangle);
            if (!t || *t >= nearest)
                continue;
            nearest = *t;
            const Eigen::Matrix4f inverse = placed.inverse();
            const Eigen::Vector3f hit = (inverse * (origin + direction * *t).homogeneous()).head<3>();
            const Eigen::Vector3f camera = (inverse * eye.homogeneous()).head<3>();
            best = SurfaceHit{state.part.ref, piece.piece.volume, triangle - piece.piece.first_triangle,
                              {hit.x(), hit.y(), hit.z()}, {camera.x(), camera.y(), camera.z()}};
        }
    return best;
}

void Viewport::set_bed(Bed bed)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->pending_bed = std::move(bed);
    m_impl->framed = false;
}

void Viewport::set_parts(std::vector<Part> parts)
{
    std::map<const PartMesh*, std::vector<PieceState>> pieces;
    std::vector<PartState> states;
    states.reserve(parts.size());
    for (Part& part : parts) {
        if (!part.mesh)
            continue;
        auto found = pieces.find(part.mesh.get());
        if (found == pieces.end())
            found = pieces.emplace(part.mesh.get(), pieces_of(*part.mesh)).first;
        const Eigen::Matrix4f transform = to_matrix(part.transform);
        states.push_back({std::move(part), transform, found->second});
    }
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    Impl& d = *m_impl;
    d.parts = std::move(states);
    if (d.selection && std::none_of(d.parts.begin(), d.parts.end(),
                                    [&](const PartState& s) { return s.part.ref == *d.selection; }))
        d.selection.reset();
    d.frame_scene_locked();
}

void Viewport::clear_parts()
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->parts.clear();
    m_impl->selection.reset();
}

void Viewport::set_part_transform(const PartRef& ref, const std::array<float, 16>& transform)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    for (PartState& state : m_impl->parts)
        if (state.part.ref == ref) {
            state.part.transform = transform;
            state.transform = to_matrix(transform);
        }
}

void Viewport::set_selection(std::optional<PartRef> selection)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->selection = selection;
}

void Viewport::set_volume_transform(int object, int volume, const std::array<float, 16>& transform)
{
    const Eigen::Matrix4f matrix = to_matrix(transform);
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    for (PartState& state : m_impl->parts) {
        if (state.part.ref.object != object)
            continue;
        for (PieceState& piece : state.pieces)
            if (piece.piece.volume == volume)
                piece.transform = matrix;
    }
}

std::optional<int> Viewport::highlighted_volume() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    return m_impl->highlighted_volume;
}

void Viewport::set_highlighted_volume(std::optional<int> volume)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->highlighted_volume = volume;
}

std::optional<PartRef> Viewport::selection() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    return m_impl->selection;
}

std::optional<PartRef> Viewport::pick(float x_px, float y_px) const
{
    const auto hit = pick_face(x_px, y_px);
    if (!hit)
        return std::nullopt;
    return hit->part;
}

std::optional<Viewport::VolumeHit> Viewport::pick_volume(float x_px, float y_px) const
{
    std::vector<PartState> parts;
    Eigen::Vector3f origin, direction;
    {
        std::lock_guard<std::mutex> lock(m_impl->mutex);
        if (m_impl->has_toolpaths)
            return std::nullopt;   // the preview shows no parts
        m_impl->camera.ray(x_px, y_px, origin, direction);
        parts = m_impl->parts;
    }
    std::optional<VolumeHit> part_hit, other_hit;
    float part_t = std::numeric_limits<float>::infinity();
    float other_t = std::numeric_limits<float>::infinity();
    for (const PartState& state : parts)
        for (const PieceState& piece : state.pieces) {
            const std::optional<float> t = intersect(*state.part.mesh, piece.piece.first_triangle, piece.piece.triangle_count,
                                                     piece.box, state.transform * piece.transform, origin, direction);
            if (!t)
                continue;
            const bool is_part = piece.piece.kind == VolumeKind::ModelPart;
            float& nearest = is_part ? part_t : other_t;
            if (*t < nearest) {
                nearest = *t;
                (is_part ? part_hit : other_hit) = VolumeHit{state.part.ref, piece.piece.volume, piece.piece.kind};
            }
        }
    return other_hit ? other_hit : part_hit;
}

std::optional<float> Viewport::vertical_drag(const std::array<float, 3>& anchor, float x0, float y0, float x1, float y1) const
{
    Eigen::Vector3f o0, d0, o1, d1;
    Eigen::Vector3f forward;
    {
        std::lock_guard<std::mutex> lock(m_impl->mutex);
        m_impl->camera.ray(x0, y0, o0, d0);
        m_impl->camera.ray(x1, y1, o1, d1);
    }
    // The upright plane through the anchor, facing the camera horizontally.
    Eigen::Vector3f normal(d0.x(), d0.y(), 0.0f);
    if (normal.norm() < 1e-4f)
        return std::nullopt;   // looking straight down: no upright plane faces the camera
    normal.normalize();
    const Eigen::Vector3f a(anchor[0], anchor[1], anchor[2]);
    const auto hit = [&](const Eigen::Vector3f& o, const Eigen::Vector3f& d) -> std::optional<Eigen::Vector3f> {
        const float denominator = d.dot(normal);
        if (std::abs(denominator) < 1e-6f)
            return std::nullopt;
        return o + d * ((a - o).dot(normal) / denominator);
    };
    const auto p0 = hit(o0, d0);
    const auto p1 = hit(o1, d1);
    if (!p0 || !p1)
        return std::nullopt;
    return p1->z() - p0->z();
}

std::optional<Viewport::FaceHit> Viewport::pick_face(float x_px, float y_px) const
{
    std::vector<PartState> parts;
    Eigen::Vector3f origin, direction;
    {
        std::lock_guard<std::mutex> lock(m_impl->mutex);
        if (m_impl->has_toolpaths)
            return std::nullopt;
        m_impl->camera.ray(x_px, y_px, origin, direction);
        parts = m_impl->parts;
    }
    // Only parts: the other volumes are drawn see-through and not printed.
    std::optional<FaceHit> best;
    float nearest = std::numeric_limits<float>::infinity();
    for (const PartState& state : parts)
        for (const PieceState& piece : state.pieces) {
            if (piece.piece.kind != VolumeKind::ModelPart)
                continue;
            const Eigen::Matrix4f placed = state.transform * piece.transform;
            std::size_t triangle = 0;
            const std::optional<float> t = intersect(*state.part.mesh, piece.piece.first_triangle, piece.piece.triangle_count,
                                                     piece.box, placed, origin, direction, &triangle);
            if (t && *t < nearest) {
                nearest = *t;
                Eigen::Vector3f n = world_normal(*state.part.mesh, triangle, placed);
                // The face seen is the one facing the camera, whatever the mesh's winding says.
                if (n.dot(direction) > 0.0f)
                    n = -n;
                best = FaceHit{state.part.ref, {n.x(), n.y(), n.z()}};
            }
        }
    return best;
}

std::optional<std::array<float, 2>> Viewport::bed_point(float x_px, float y_px) const
{
    Eigen::Vector3f origin, direction;
    {
        std::lock_guard<std::mutex> lock(m_impl->mutex);
        m_impl->camera.ray(x_px, y_px, origin, direction);
    }
    if (std::abs(direction.z()) < 1e-6f)
        return std::nullopt;
    const float t = -origin.z() / direction.z();
    if (t <= 0)
        return std::nullopt;
    const Eigen::Vector3f hit = origin + direction * t;
    return std::array<float, 2>{hit.x(), hit.y()};
}

void Viewport::set_overlay(std::shared_ptr<const Overlay> overlay)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->overlay = std::move(overlay);
}

void Viewport::set_cut_view(std::optional<CutView> view)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->cut_view = view;
}

void Viewport::ray(float x_px, float y_px, std::array<float, 3>& origin, std::array<float, 3>& direction) const
{
    Eigen::Vector3f o, d;
    {
        std::lock_guard<std::mutex> lock(m_impl->mutex);
        m_impl->camera.ray(x_px, y_px, o, d);
    }
    origin = {o.x(), o.y(), o.z()};
    direction = {d.x(), d.y(), d.z()};
}

std::optional<std::array<float, 3>> Viewport::plane_point(float x_px, float y_px, const std::array<float, 3>& origin,
                                                          const std::array<float, 3>& normal) const
{
    Eigen::Vector3f o, d;
    {
        std::lock_guard<std::mutex> lock(m_impl->mutex);
        m_impl->camera.ray(x_px, y_px, o, d);
    }
    const Eigen::Vector3f n(normal[0], normal[1], normal[2]);
    const Eigen::Vector3f p(origin[0], origin[1], origin[2]);
    const float denominator = d.dot(n);
    if (std::abs(denominator) < 1e-6f)
        return std::nullopt;
    const float t = (p - o).dot(n) / denominator;
    if (t <= 0)
        return std::nullopt;
    const Eigen::Vector3f hit = o + d * t;
    return std::array<float, 3>{hit.x(), hit.y(), hit.z()};
}

void Viewport::set_toolpaths(std::shared_ptr<const libvgcode::GCodeInputData> toolpaths)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->toolpaths = std::move(toolpaths);
    m_impl->toolpaths_dirty = true;
    m_impl->toolpaths_restoring = false;
    m_impl->layer_range = {0, static_cast<std::size_t>(-1)};
}

void Viewport::clear_toolpaths()
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    if (!m_impl->toolpaths)
        return;
    m_impl->toolpaths.reset();
    m_impl->toolpaths_dirty = true;
    m_impl->toolpaths_restoring = false;
}

void Viewport::set_layer_range(std::size_t first, std::size_t last)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->layer_range = {first, last};
    m_impl->toolpath_view_dirty = true;
}

void Viewport::set_toolpath_coloring(ToolpathColoring coloring)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->coloring = coloring;
    m_impl->toolpath_view_dirty = true;
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
