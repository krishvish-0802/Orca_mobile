#pragma once

#include <Eigen/Geometry>

#include <optional>

#include "orca_viewport/Viewport.hpp"

namespace orca::viewport {

// Distance along a world-space ray (unit direction) to the nearest triangle of
// `mesh` placed by `transform`, if the ray hits it. `local_box` bounds the mesh
// in its own coordinates and rejects misses early.
std::optional<float> intersect(const PartMesh& mesh, const Eigen::AlignedBox3f& local_box,
                               const Eigen::Matrix4f& transform, const Eigen::Vector3f& origin,
                               const Eigen::Vector3f& direction);

Eigen::AlignedBox3f bounds_of(const PartMesh& mesh);

} // namespace orca::viewport
