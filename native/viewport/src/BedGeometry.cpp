#include "BedGeometry.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>

namespace orca::viewport {

namespace {
constexpr float kPlateZ = -0.05f;   // just under the model so faces on the bed don't flicker
constexpr float kLineZ = 0.02f;
constexpr float kFloorZ = -0.05f;   // level with the plate, which never covers it

using Outline = std::vector<std::array<float, 2>>;

void push(std::vector<float>& out, float x, float y, float z)
{
    out.push_back(x);
    out.push_back(y);
    out.push_back(z);
}
// Where the line `coordinate` = c (along axis 1 - axis) crosses the outline: the
// span of the other coordinate inside it. Beds are convex, so one span at most.
bool span_inside(const Outline& outline, int axis, float c, float& from, float& to)
{
    const int other = 1 - axis;
    from = std::numeric_limits<float>::max();
    to = std::numeric_limits<float>::lowest();
    for (size_t i = 0; i < outline.size(); ++i) {
        const auto& a = outline[i];
        const auto& b = outline[(i + 1) % outline.size()];
        const float lo = std::min(a[axis], b[axis]);
        const float hi = std::max(a[axis], b[axis]);
        if (c < lo || c > hi)
            continue;
        if (a[axis] == b[axis]) {   // edge along the line
            from = std::min({from, a[other], b[other]});
            to = std::max({to, a[other], b[other]});
            continue;
        }
        const float t = (c - a[axis]) / (b[axis] - a[axis]);
        const float v = a[other] + t * (b[other] - a[other]);
        from = std::min(from, v);
        to = std::max(to, v);
    }
    return from <= to;
}

// A line at `coordinate` = c from `lo` to `hi` along the other axis.
void push_line(std::vector<float>& out, int axis, float c, float lo, float hi, float z)
{
    if (hi - lo < 1e-3f)
        return;
    if (axis == 0) {   // x = c, running along y
        push(out, c, lo, z);
        push(out, c, hi, z);
    } else {           // y = c, running along x
        push(out, lo, c, z);
        push(out, hi, c, z);
    }
}
} // namespace

BedGeometry build_bed_geometry(const Bed& bed, float grid_step_mm, float floor_margin_mm)
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

    // Grid lines on one 10 mm pitch: the part of each line inside the bed is
    // the bed's grid, the rest is floor.
    if (grid_step_mm > 0) {
        const float margin = std::max(floor_margin_mm,
                                      std::max(geometry.max_x - geometry.min_x, geometry.max_y - geometry.min_y));
        const float lo[2] = {geometry.min_x - margin, geometry.min_y - margin};
        const float hi[2] = {geometry.max_x + margin, geometry.max_y + margin};
        for (int axis = 0; axis < 2; ++axis) {
            const int other = 1 - axis;
            for (float c = std::ceil(lo[axis] / grid_step_mm) * grid_step_mm; c <= hi[axis]; c += grid_step_mm) {
                float from = 0, to = 0;
                if (span_inside(bed.outline, axis, c, from, to)) {
                    push_line(geometry.grid_lines, axis, c, from, to, kLineZ);
                    push_line(geometry.floor_lines, axis, c, lo[other], from, kFloorZ);
                    push_line(geometry.floor_lines, axis, c, to, hi[other], kFloorZ);
                } else {
                    push_line(geometry.floor_lines, axis, c, lo[other], hi[other], kFloorZ);
                }
            }
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
