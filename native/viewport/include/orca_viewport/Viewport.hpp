#pragma once

#include <array>
#include <memory>
#include <vector>

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

    // Gestures, in screen pixels.
    void orbit(float dx_px, float dy_px);
    void pan(float dx_px, float dy_px);
    void zoom(float factor, float focus_x_px, float focus_y_px);
    // Frames the model if there is one, else the bed.
    void fit();

private:
    struct Impl;
    std::unique_ptr<Impl> m_impl;
};

} // namespace orca::viewport
