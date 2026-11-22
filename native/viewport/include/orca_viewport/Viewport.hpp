#pragma once

#include <array>
#include <cstddef>
#include <memory>
#include <optional>
#include <vector>

namespace libvgcode { struct GCodeInputData; }

namespace orca::viewport {

// Printable area: outline in mm on the XY plane, and the maximum print height.
struct Bed {
    std::vector<std::array<float, 2>> outline;
    float max_height = 0;
};

// What a volume of an object is, as OrcaSlicer's ModelVolumeType: values are
// shared with the bridge (orca_engine::VolumeKind).
enum class VolumeKind : int {
    ModelPart = 0,
    NegativeVolume = 1,
    ParameterModifier = 2,
    SupportBlocker = 3,
    SupportEnforcer = 4,
    SeamCenter = 5,
    SeamLeft = 6,
    SeamRight = 7,
    SeamEnforced = 8,
    SeamBlocked = 9,
    SeamNeutral = 10,
    PaintedModifier = 11,
};

// A run of an object mesh's triangles that belongs to one of its volumes, in
// that volume's coordinates; `transform` (column-major 4x4) places the volume in
// its object.
struct MeshPiece {
    int volume = 0;                  // index in the object's volumes
    VolumeKind kind = VolumeKind::ModelPart;
    std::size_t first_triangle = 0;
    std::size_t triangle_count = 0;
    std::array<float, 16> transform{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
};

// An object's triangles: x, y, z per vertex, three vertices per triangle.
// Shared by every instance of the object. `pieces` says which volume each run
// of triangles is; without pieces the whole mesh is one model part in object
// coordinates.
struct PartMesh {
    std::vector<float> positions;
    std::vector<MeshPiece> pieces;
};

// Identifies an instance on the plate: object and instance index, as the engine numbers them.
struct PartRef {
    int object = 0;
    int instance = 0;
    bool operator==(const PartRef& other) const { return object == other.object && instance == other.instance; }
};

// One instance to draw: its object's mesh and its placement, column-major 4x4.
struct Part {
    PartRef ref;
    std::shared_ptr<const PartMesh> mesh;
    std::array<float, 16> transform{};
};

// Painted triangles of one volume, in its own coordinates, by state (what was
// painted: an enforcer, a blocker, a filament...).
struct PaintOverlayMesh {
    struct Run {
        int state = 0;
        std::size_t first_triangle = 0;
        std::size_t triangle_count = 0;
    };
    std::vector<float> positions;
    std::vector<Run> runs;
};

// Tool geometry drawn over the plate (a cut plane and its section, connectors,
// measured features), in world coordinates.
struct Overlay {
    struct Shape {
        std::vector<float> positions;      // x, y, z per vertex
        bool lines = false;                // pairs of vertices; else triples
        std::array<float, 4> color{1, 1, 1, 1};   // alpha < 1 draws it see-through
        bool on_top = false;               // not hidden by the model
    };
    std::vector<Shape> shapes;
};

// An object split by a cut plane: its parts above the plane drawn in one color,
// below in another, a side that is not kept greyed. `plane` is (a, b, c, d) with
// a x + b y + c z + d > 0 above, in world coordinates.
struct CutView {
    int object = 0;
    std::array<float, 4> plane{0, 0, 1, 0};
    bool keep_above = true;
    bool keep_below = true;
    bool hide_above = false;   // show the cut face: nothing above the plane is drawn
};

// What colors the toolpaths. Values are shared with the app (ToolpathColoring.kt).
enum class ToolpathColoring : int {
    FeatureType = 0,
    Height = 1,
    Width = 2,
    Speed = 3,
    FanSpeed = 4,
    Temperature = 5,
    VolumetricFlowRate = 6,
    LayerTime = 7,
    Filament = 8,
};

// The plate view. Rendering methods run on the GL thread; the scene and camera
// methods may be called from any thread and take effect at the next frame.
// World axes follow OrcaSlicer: X right, Y back, Z up, the bed at z = 0.
class Viewport {
public:
    Viewport();
    ~Viewport();
    Viewport(const Viewport&) = delete;
    Viewport& operator=(const Viewport&) = delete;

    // --- GL thread ---
    // A new GL context: (re)create all GPU resources.
    void on_surface_created();
    void on_surface_changed(int width_px, int height_px);
    void draw();

    // --- any thread ---
    void set_bed(Bed bed);
    // Replaces the instances on the plate (and drops a selection that no longer exists).
    void set_parts(std::vector<Part> parts);
    void clear_parts();
    // Moves an instance already on the plate.
    void set_part_transform(const PartRef& ref, const std::array<float, 16>& transform);
    // Highlights one instance, or none.
    void set_selection(std::optional<PartRef> selection);
    // Highlights one volume of the selected instance's object (index in its
    // volumes), or none.
    void set_highlighted_volume(std::optional<int> volume);
    std::optional<PartRef> selection() const;

    // The instance under a screen point, nearest first; none if the point hits no part.
    std::optional<PartRef> pick(float x_px, float y_px) const;
    // The volume under a screen point: the nearest volume that is not a part
    // (they are drawn see-through, so one inside a part can be tapped), else the
    // nearest part.
    struct VolumeHit {
        PartRef part;
        int volume = 0;
        VolumeKind kind = VolumeKind::ModelPart;
    };
    std::optional<VolumeHit> pick_volume(float x_px, float y_px) const;
    // Moves a volume within its object (every instance of it shows the change).
    void set_volume_transform(int object, int volume, const std::array<float, 16>& transform);
    std::optional<int> highlighted_volume() const;
    // How far a vertical drag from (x0, y0) to (x1, y1) moves a point at
    // `anchor` (world, mm) up or down: on the upright plane through it facing the camera.
    std::optional<float> vertical_drag(const std::array<float, 3>& anchor, float x0, float y0, float x1, float y1) const;

    // Where a screen point lands on a part, for painting: the triangle hit (index
    // in its volume's mesh) and the hit point and the camera in that volume's
    // own coordinates.
    struct SurfaceHit {
        PartRef part;
        int volume = 0;
        std::size_t triangle = 0;
        std::array<float, 3> hit{};
        std::array<float, 3> camera{};
    };
    std::optional<SurfaceHit> pick_surface(float x_px, float y_px) const;

    // Paint drawn over a volume of an object (every instance of it); null removes it.
    void set_paint_overlay(int object, int volume, std::shared_ptr<const PaintOverlayMesh> overlay);
    void clear_paint_overlays();
    // Colors by painted state, RGBA; states past the end use the last.
    void set_paint_palette(std::vector<std::array<float, 4>> palette);

    // The instance under a screen point and the normal (world, unit) of the face
    // hit there, turned toward the camera.
    struct FaceHit {
        PartRef part;
        std::array<float, 3> normal{};
    };
    std::optional<FaceHit> pick_face(float x_px, float y_px) const;
    // Where a screen point lands on the bed plane (z = 0), if it does.
    std::optional<std::array<float, 2>> bed_point(float x_px, float y_px) const;

    // Tool geometry over the plate; null removes it.
    void set_overlay(std::shared_ptr<const Overlay> overlay);
    // Splits an object's colors by a cut plane, or stops.
    void set_cut_view(std::optional<CutView> view);
    // Where a screen point lands on the plane through `origin` with `normal`
    // (world), if it does, in front of the camera.
    std::optional<std::array<float, 3>> plane_point(float x_px, float y_px, const std::array<float, 3>& origin,
                                                    const std::array<float, 3>& normal) const;
    // The camera's eye and the ray through a screen point (unit), in world coordinates.
    void ray(float x_px, float y_px, std::array<float, 3>& origin, std::array<float, 3>& direction) const;

    // Sliced toolpaths, drawn instead of the model while set. Shared: the caller
    // may keep its copy, nothing here modifies it.
    void set_toolpaths(std::shared_ptr<const libvgcode::GCodeInputData> toolpaths);
    void clear_toolpaths();
    // Shows toolpath layers first..last (0-based, inclusive).
    void set_layer_range(std::size_t first, std::size_t last);
    void set_toolpath_coloring(ToolpathColoring coloring);

    // Gestures, in screen pixels.
    void orbit(float dx_px, float dy_px);
    void pan(float dx_px, float dy_px);
    void zoom(float factor, float focus_x_px, float focus_y_px);
    // Frames the toolpaths or the model if shown, else the bed.
    void fit();

private:
    struct Impl;
    std::unique_ptr<Impl> m_impl;
};

} // namespace orca::viewport
