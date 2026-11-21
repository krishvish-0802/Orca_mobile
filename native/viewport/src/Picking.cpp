#include "Picking.hpp"

#include <cmath>
#include <limits>

namespace orca::viewport {

namespace {

// Slab test; t range along the ray where it is inside the box.
bool hits_box(const Eigen::AlignedBox3f& box, const Eigen::Vector3f& o, const Eigen::Vector3f& d)
{
    float t_min = 0.0f;
    float t_max = std::numeric_limits<float>::infinity();
    for (int axis = 0; axis < 3; ++axis) {
        if (std::abs(d[axis]) < 1e-12f) {
            if (o[axis] < box.min()[axis] || o[axis] > box.max()[axis])
                return false;
            continue;
        }
        float t0 = (box.min()[axis] - o[axis]) / d[axis];
        float t1 = (box.max()[axis] - o[axis]) / d[axis];
        if (t0 > t1)
            std::swap(t0, t1);
        t_min = std::max(t_min, t0);
        t_max = std::min(t_max, t1);
        if (t_min > t_max)
            return false;
    }
    return true;
}

} // namespace

Eigen::AlignedBox3f bounds_of(const PartMesh& mesh)
{
    Eigen::AlignedBox3f box;
    const auto& p = mesh.positions;
    for (size_t i = 0; i + 2 < p.size(); i += 3)
        box.extend(Eigen::Vector3f(p[i], p[i + 1], p[i + 2]));
    return box;
}

std::optional<float> intersect(const PartMesh& mesh, const Eigen::AlignedBox3f& local_box,
                               const Eigen::Matrix4f& transform, const Eigen::Vector3f& origin,
                               const Eigen::Vector3f& direction)
{
    if (local_box.isEmpty())
        return std::nullopt;
    // Into object coordinates. The direction is not renormalized, so a hit's
    // parameter there is the world distance along the unit world ray.
    const Eigen::Matrix4f inverse = transform.inverse();
    const Eigen::Vector3f o = (inverse * origin.homogeneous()).head<3>();
    const Eigen::Vector3f d = inverse.topLeftCorner<3, 3>() * direction;
    if (!hits_box(local_box, o, d))
        return std::nullopt;

    // Moller-Trumbore, both faces.
    constexpr float kEpsilon = 1e-9f;
    float nearest = std::numeric_limits<float>::infinity();
    const auto& p = mesh.positions;
    for (size_t i = 0; i + 8 < p.size(); i += 9) {
        const Eigen::Vector3f v0(p[i], p[i + 1], p[i + 2]);
        const Eigen::Vector3f e1 = Eigen::Vector3f(p[i + 3], p[i + 4], p[i + 5]) - v0;
        const Eigen::Vector3f e2 = Eigen::Vector3f(p[i + 6], p[i + 7], p[i + 8]) - v0;
        const Eigen::Vector3f h = d.cross(e2);
        const float a = e1.dot(h);
        if (std::abs(a) < kEpsilon)
            continue;
        const float f = 1.0f / a;
        const Eigen::Vector3f s = o - v0;
        const float u = f * s.dot(h);
        if (u < 0.0f || u > 1.0f)
            continue;
        const Eigen::Vector3f q = s.cross(e1);
        const float v = f * d.dot(q);
        if (v < 0.0f || u + v > 1.0f)
            continue;
        const float t = f * e2.dot(q);
        if (t > 0.0f && t < nearest)
            nearest = t;
    }
    if (std::isinf(nearest))
        return std::nullopt;
    return nearest;
}

} // namespace orca::viewport
