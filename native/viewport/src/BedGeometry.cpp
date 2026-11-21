#include "BedGeometry.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace orca::viewport {

namespace {
constexpr float kPlateZ = -0.05f;   // just under the model so faces on the bed don't flicker
constexpr float kLineZ = 0.02f;

void push(std::vector<float>& out, float x, float y, float z)
{
    out.push_back(x);
    out.push_back(y);
    out.push_back(z);
}
} // namespace

BedGeometry build_bed_geometry(const Bed& bed, float grid_step_mm)
{
    BedGeometry geometry;
    if (bed.outline.size() < 3)
        return geometry;

    geometry.min_x = geometry.min_y = std::numeric_limits<float>::max();
    geometry.max_x = geometry.max_y = std::numeric_limits<float>::lowest();
    for (const auto& p : bed.outline) {
        geometry.min_x = std::min(geometry.min_x, p[0]);
        geometry.max_x = std::max(geometry.max_x, p[0]);
        geometry.min_y = std::min(geometry.min_y, p[1]);
        geometry.max_y = std::max(geometry.max_y, p[1]);
    }

    // Plate: fan from the first vertex.
    const auto& origin = bed.outline.front();
    for (size_t i = 1; i + 1 < bed.outline.size(); ++i) {
        for (const auto* p : {&origin, &bed.outline[i], &bed.outline[i + 1]}) {
            push(geometry.plate_positions, (*p)[0], (*p)[1], kPlateZ);
            push(geometry.plate_normals, 0, 0, 1);
        }
    }

    // Grid lines across the bounds.
    if (grid_step_mm > 0) {
        for (float x = std::ceil(geometry.min_x / grid_step_mm) * grid_step_mm; x <= geometry.max_x; x += grid_step_mm) {
            push(geometry.grid_lines, x, geometry.min_y, kLineZ);
            push(geometry.grid_lines, x, geometry.max_y, kLineZ);
        }
        for (float y = std::ceil(geometry.min_y / grid_step_mm) * grid_step_mm; y <= geometry.max_y; y += grid_step_mm) {
            push(geometry.grid_lines, geometry.min_x, y, kLineZ);
            push(geometry.grid_lines, geometry.max_x, y, kLineZ);
        }
    }

    // Outline.
    for (size_t i = 0; i < bed.outline.size(); ++i) {
        const auto& a = bed.outline[i];
        const auto& b = bed.outline[(i + 1) % bed.outline.size()];
        push(geometry.outline_lines, a[0], a[1], kLineZ);
        push(geometry.outline_lines, b[0], b[1], kLineZ);
    }
    return geometry;
}

} // namespace orca::viewport
