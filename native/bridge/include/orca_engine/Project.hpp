#pragma once

#include <array>
#include <cstddef>
#include <memory>
#include <string>
#include <vector>

namespace Slic3r { class Model; }

namespace orca::engine {

// One object of a loaded project, as the UI lists it.
struct ObjectSummary {
    std::string name;
    std::size_t instance_count = 0;
    std::size_t triangle_count = 0;
    std::array<double, 3> size{};     // mm, of the first instance
};

// Triangles ready to draw: three vertices per triangle, world coordinates (mm),
// with a face normal repeated per vertex for flat shading.
struct RenderMesh {
    std::vector<float> positions;     // x, y, z per vertex
    std::vector<float> normals;       // nx, ny, nz per vertex
    std::array<float, 3> min{};       // bounding box
    std::array<float, 3> max{};
    std::size_t triangle_count() const { return positions.size() / 9; }
};

// A model loaded for editing and slicing: the objects of one or more imported
// files (STL, 3MF, OBJ, STEP, ...), placed on the bed.
class Project {
public:
    // Loads `path`. Throws std::runtime_error if the file cannot be read or holds
    // no printable object.
    static std::unique_ptr<Project> load(const std::string& path);
    ~Project();
    Project(const Project&) = delete;
    Project& operator=(const Project&) = delete;

    const std::string& source_path() const;
    std::vector<ObjectSummary> objects() const;

    // Moves every instance so the objects sit together, centered on (x, y), on the bed.
    void center_on(double x, double y);

    // All instances' triangles in world space, for the viewport.
    RenderMesh render_mesh() const;

    // The underlying model, for the slicing pipeline inside the bridge.
    Slic3r::Model& model();
    const Slic3r::Model& model() const;

private:
    Project();
    struct Impl;
    std::unique_ptr<Impl> m_impl;
};

} // namespace orca::engine
