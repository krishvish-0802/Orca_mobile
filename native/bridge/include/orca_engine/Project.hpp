#pragma once

#include <array>
#include <cstddef>
#include <functional>
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

// An instance's transform as the desktop's object manipulation panel shows it.
struct InstanceTransform {
    std::array<double, 3> position{};        // mm: the center of the object's box on the bed
    std::array<double, 3> rotation_deg{};    // Euler angles about X, Y, Z
    std::array<double, 3> scale{1, 1, 1};    // factors along the object's own axes
    std::array<double, 3> size{};            // mm: the object's own box, scaled
    std::array<bool, 3> mirrored{};
};

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

    // The object manipulation panel. Every change leaves the instance standing on
    // the bed. Throws std::out_of_range for a bad reference, std::invalid_argument
    // for a scale that is not positive or an axis other than 0..2.
    InstanceTransform transform(const InstanceRef& ref) const;
    void set_position(const InstanceRef& ref, double x, double y);
    void set_rotation(const InstanceRef& ref, const std::array<double, 3>& degrees);
    void set_scale(const InstanceRef& ref, const std::array<double, 3>& factors);
    void mirror(const InstanceRef& ref, int axis);
    // No rotation, scale 100 %, not mirrored.
    void reset_transform(const InstanceRef& ref);
    // Turns the instance so the flat side nearest `world_normal` (a face of its
    // convex hull, as the desktop's Lay on face offers) lies on the bed.
    void lay_on_face(const InstanceRef& ref, const std::array<double, 3>& world_normal);

    // Takes an instance off the plate; the object goes too when it was its last.
    // Throws std::out_of_range for a bad reference.
    void remove(const InstanceRef& ref);

    // A snapshot of the model, for the slicing pipeline inside the bridge.
    Slic3r::Model model_copy() const;
    // Runs `change` on the model under the project's lock, for the bridge's plate
    // tools (see PlateTools.hpp).
    void edit(const std::function<void(Slic3r::Model&)>& change);

private:
    Project();
    struct Impl;
    std::unique_ptr<Impl> m_impl;
};

} // namespace orca::engine
