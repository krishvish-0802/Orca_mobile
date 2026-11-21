#pragma once

#include <Eigen/Core>

#include <array>
#include <cstddef>
#include <memory>

namespace libvgcode {
class Viewer;
struct GCodeInputData;
}

namespace orca::viewport {

enum class ToolpathColoring : int;

// Draws sliced toolpaths with libvgcode, OrcaSlicer's own G-code renderer.
// Every method runs on the GL thread.
class ToolpathRenderer {
public:
    ToolpathRenderer();
    ~ToolpathRenderer();

    // A new GL context: whatever was uploaded died with the old one.
    void on_context_created();

    // Uploads toolpaths, replacing any shown. Returns false if the GPU setup failed.
    bool load(const libvgcode::GCodeInputData& toolpaths);
    void release();
    bool loaded() const { return m_loaded; }

    std::size_t layer_count() const;
    // Shows layers first..last (0-based, inclusive), clamped to what exists.
    void set_layer_range(std::size_t first, std::size_t last);
    void set_coloring(ToolpathColoring coloring);

    // Bounds of the extrusions, mm.
    std::array<Eigen::Vector3f, 2> bounds() const;

    void render(const Eigen::Matrix4f& view, const Eigen::Matrix4f& projection);

private:
    bool ensure_initialized();

    std::unique_ptr<libvgcode::Viewer> m_viewer;
    bool m_initialized = false;
    bool m_loaded = false;
};

} // namespace orca::viewport
