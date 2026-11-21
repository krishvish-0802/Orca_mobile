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

// One instance of one object on the plate.
struct InstanceRef {
    std::size_t object = 0;
    std::size_t instance = 0;
    bool operator==(const InstanceRef& other) const { return object == other.object && instance == other.instance; }
};

// An instance's placement: column-major 4x4, object coordinates to bed (mm).
using Placement = std::array<double, 16>;

// A model loaded for editing and slicing: the objects of one or more imported
// files (STL, 3MF, OBJ, STEP, ...), placed on the bed. Thread-safe: the UI edits
// it while a slice may be copying it on another thread.
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

    // An object's triangles in its own coordinates, shared by its instances:
    // x, y, z per vertex, three vertices per triangle. Empty for a bad index.
    std::vector<float> object_triangles(std::size_t object) const;
    std::vector<InstanceRef> instances() const;
    // Throws std::out_of_range for a bad reference.
    Placement placement(const InstanceRef& ref) const;

    // Slides an instance across the bed.
    void translate(const InstanceRef& ref, double dx, double dy);
    // Turns an instance about the vertical axis through the center of its bounding box.
    void rotate_z(const InstanceRef& ref, double radians);

    // A snapshot of the model, for the slicing pipeline inside the bridge.
    Slic3r::Model model_copy() const;

private:
    Project();
    struct Impl;
    std::unique_ptr<Impl> m_impl;
};

} // namespace orca::engine
