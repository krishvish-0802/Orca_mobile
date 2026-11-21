#pragma once

#include <vector>

#include "orca_viewport/Viewport.hpp"

namespace orca::viewport {

// Vertex data for drawing a bed: a filled plate (lit triangles with normals),
// grid lines every 10 mm inside its bounds, and the outline.
struct BedGeometry {
    std::vector<float> plate_positions;
    std::vector<float> plate_normals;
    std::vector<float> grid_lines;      // pairs of xyz points
    std::vector<float> outline_lines;   // pairs of xyz points
    float min_x = 0, min_y = 0, max_x = 0, max_y = 0;
};

// Triangulates the outline as a fan, which suits the convex beds printers have.
BedGeometry build_bed_geometry(const Bed& bed, float grid_step_mm = 10.0f);

} // namespace orca::viewport
