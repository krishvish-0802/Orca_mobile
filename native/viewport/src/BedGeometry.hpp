#pragma once

#include <vector>

#include "orca_viewport/Viewport.hpp"

namespace orca::viewport {

// Vertex data for drawing a bed: a filled plate (lit triangles with normals),
// grid lines every 10 mm inside it, the outline, and a floor grid on the same
// 10 mm pitch around it, so the plate sits on an endless-looking grid.
struct BedGeometry {
    std::vector<float> plate_positions;
    std::vector<float> plate_normals;
    std::vector<float> grid_lines;      // pairs of xyz points, inside the bed
    std::vector<float> floor_lines;     // pairs of xyz points, outside the bed
    std::vector<float> outline_lines;   // pairs of xyz points
    float min_x = 0, min_y = 0, max_x = 0, max_y = 0;
};

// Triangulates the outline as a fan, which suits the convex beds printers have.
// The floor grid reaches `floor_margin_mm` beyond the bed's bounds on each side,
// at least one bed size.
BedGeometry build_bed_geometry(const Bed& bed, float grid_step_mm = 10.0f, float floor_margin_mm = 400.0f);

} // namespace orca::viewport
