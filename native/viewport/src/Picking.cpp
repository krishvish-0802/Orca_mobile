#include "Picking.hpp"

#include <algorithm>
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

Eigen::AlignedBox3f bounds_of(const PartMesh& mesh, std::size_t first, std::size_t count)
{
    Eigen::AlignedBox3f box;
    const auto& p = mesh.positions;
    const std::size_t end = std::min(p.size(), (first + count) * 9);
    for (std::size_t i = first * 9; i + 2 < end; i += 3)
        box.extend(Eigen::Vector3f(p[i], p[i + 1], p[i + 2]));
    return box;
}

std::optional<float> intersect(const PartMesh& mesh, std::size_t first, std::size_t count,
                               const Eigen::AlignedBox3f& local_box, const Eigen::Matrix4f& transform,
                               const Eigen::Vector3f& origin, const Eigen::Vector3f& direction,
                               std::size_t* triangle)
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
    const size_t end = std::min(p.size(), (first + count) * 9);
    for (size_t i = first * 9; i + 8 < end; i += 9) {
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
        if (t > 0.0f && t < nearest) {
            nearest = t;
            if (triangle != nullptr)
                *triangle = i / 9;
        }
    }
    if (std::isinf(nearest))
        return std::nullopt;
    return nearest;
}

Eigen::Vector3f world_normal(const PartMesh& mesh, std::size_t index, const Eigen::Matrix4f& transform)
{
    const auto& p = mesh.positions;
    const std::size_t i = index * 9;
    if (i + 8 >= p.size())
        return Eigen::Vector3f::Zero();
    const Eigen::Vector3f v0(p[i], p[i + 1], p[i + 2]);
    const Eigen::Vector3f v1(p[i + 3], p[i + 4], p[i + 5]);
    const Eigen::Vector3f v2(p[i + 6], p[i + 7], p[i + 8]);
    const Eigen::Vector3f local = (v1 - v0).cross(v2 - v0);
    // Normals go through the inverse transpose of the linear part.
    const Eigen::Matrix3f linear = transform.topLeftCorner<3, 3>();
    Eigen::Vector3f n = linear.inverse().transpose() * local;
    if (linear.determinant() < 0.0f)
        n = -n;
    const float length = n.norm();
    return length > 0.0f ? Eigen::Vector3f(n / length) : Eigen::Vector3f::Zero();
}

} // namespace orca::viewport
