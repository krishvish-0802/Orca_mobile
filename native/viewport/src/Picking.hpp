#pragma once

#include <Eigen/Geometry>

#include <optional>

#include "orca_viewport/Viewport.hpp"

namespace orca::viewport {

// Distance along a world-space ray (unit direction) to the nearest triangle of
// `mesh` placed by `transform`, if the ray hits it. `local_box` bounds the mesh
// in its own coordinates and rejects misses early.
// `triangle`, when given, receives the index of the triangle hit.
std::optional<float> intersect(const PartMesh& mesh, const Eigen::AlignedBox3f& local_box,
                               const Eigen::Matrix4f& transform, const Eigen::Vector3f& origin,
                               const Eigen::Vector3f& direction, std::size_t* triangle = nullptr);

// Unit normal of `mesh`'s triangle `index` in world coordinates, as `transform`
// places it (mirroring flips the winding, so the normal is flipped back).
Eigen::Vector3f world_normal(const PartMesh& mesh, std::size_t index, const Eigen::Matrix4f& transform);

Eigen::AlignedBox3f bounds_of(const PartMesh& mesh);

} // namespace orca::viewport
