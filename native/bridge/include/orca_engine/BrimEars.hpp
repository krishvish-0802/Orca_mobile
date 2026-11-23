#pragma once

// The desktop's Brim ears tool (GLGizmoBrimEars): round ears of brim placed by
// hand or found at an object's sharp corners. They print when the object's
// brim type is "painted".

#include <array>
#include <optional>
#include <vector>

#include "orca_engine/Project.hpp"

namespace orca::engine {

// An ear on the bed, as seen through one instance (world mm).
struct BrimEar {
    double x = 0;
    double y = 0;
    double radius = 0;
};

// Throws std::out_of_range for a bad reference.
std::vector<BrimEar> brim_ears(const Project& project, const InstanceRef& via);
// Replaces the object's ears (every instance has them, where they fall on it).
void set_brim_ears(Project& project, const InstanceRef& via, const std::vector<BrimEar>& ears);

// The instance's outline on the bed (the desktop slices it 0.1 mm up), as
// segments in world mm at z = 0.
std::vector<float> first_layer_outline(const Project& project, const InstanceRef& via);
// The point of that outline nearest (x, y), if one is within `max_distance` mm.
std::optional<std::array<double, 2>> nearest_on_outline(const Project& project, const InstanceRef& via, double x, double y,
                                                        double max_distance);

// Ears at the outline's sharp corners (GLGizmoBrimEars::auto_generate): corners
// sharper than `max_angle_deg`, looked for over `detection_length` mm. Not kept;
// add them with set_brim_ears.
std::vector<BrimEar> find_brim_ears(const Project& project, const InstanceRef& via, double max_angle_deg,
                                    double detection_length, double radius);

} // namespace orca::engine
