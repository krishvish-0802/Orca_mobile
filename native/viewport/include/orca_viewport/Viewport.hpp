#pragma once

#include <array>
#include <cstddef>
#include <memory>
#include <vector>

namespace libvgcode { struct GCodeInputData; }

namespace orca::viewport {

// Printable area: outline in mm on the XY plane, and the maximum print height.
struct Bed {
    std::vector<std::array<float, 2>> outline;
    float max_height = 0;
};

// Triangles to draw, world coordinates in mm: three vertices per triangle,
// with a normal per vertex.
struct Mesh {
    std::vector<float> positions;
    std::vector<float> normals;
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
    void set_mesh(Mesh mesh);
    void clear_mesh();

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
