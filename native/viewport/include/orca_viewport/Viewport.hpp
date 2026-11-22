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

// An object's triangles in its own coordinates (mm): x, y, z per vertex, three
// vertices per triangle. Shared by every instance of the object.
struct PartMesh {
    std::vector<float> positions;
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
    std::optional<PartRef> selection() const;

    // The instance under a screen point, nearest first; none if the point hits no part.
    std::optional<PartRef> pick(float x_px, float y_px) const;
    // The instance under a screen point and the normal (world, unit) of the face
    // hit there, turned toward the camera.
    struct FaceHit {
        PartRef part;
        std::array<float, 3> normal{};
    };
    std::optional<FaceHit> pick_face(float x_px, float y_px) const;
    // Where a screen point lands on the bed plane (z = 0), if it does.
    std::optional<std::array<float, 2>> bed_point(float x_px, float y_px) const;

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
