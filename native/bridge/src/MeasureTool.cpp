// The desktop's Measure and Assembly tools (GLGizmoMeasure, GLGizmoAssembly)
// without their gizmos: features found under taps, what lies between two,
// and the assembly steps that move one part onto another.
#include "orca_engine/MeshTools.hpp"

#include <algorithm>
#include <cmath>
#include <list>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <tuple>

#include "MeshToolsCommon.hpp"
#include "libslic3r/Geometry.hpp"
#include "libslic3r/Measure.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMesh.hpp"

namespace orca::engine {

namespace {

using Slic3r::Transform3d;
using Slic3r::Vec3d;
namespace M = Slic3r::Measure;

std::array<double, 3> to_array(const Vec3d& v) { return {v.x(), v.y(), v.z()}; }

// Building a Measuring segments the whole mesh into planes: keep the last few,
// keyed by the volume, its mesh and the linear part of its placement (the
// desktop measures a mesh with that applied, GLGizmoMeasure::register_single_mesh_pick).
class MeasuringCache {
public:
    std::shared_ptr<const M::Measuring> get(const Slic3r::ModelVolume& volume, const Transform3d& base)
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        const Key key{volume.id().id, &volume.mesh(), volume.mesh().its.indices.size(), base.matrix()};
        for (auto it = m_entries.begin(); it != m_entries.end(); ++it)
            if (it->first == key) {
                m_entries.splice(m_entries.begin(), m_entries, it);
                return it->second;
            }
        indexed_triangle_set its = volume.mesh().its;
        for (auto& vertex : its.vertices)
            vertex = (base * vertex.cast<double>()).cast<float>();
        if (base.linear().determinant() < 0.0)
            Slic3r::its_flip_triangles(its);
        auto measuring = std::make_shared<const M::Measuring>(its);
        m_entries.emplace_front(key, measuring);
        if (m_entries.size() > kSize)
            m_entries.pop_back();
        return measuring;
    }

private:
    struct Key {
        std::size_t id;
        const void* mesh;
        std::size_t triangles;
        Eigen::Matrix4d matrix;
        bool operator==(const Key& o) const
        {
            return id == o.id && mesh == o.mesh && triangles == o.triangles && matrix.isApprox(o.matrix, 1e-12);
        }
    };
    static constexpr std::size_t kSize = 4;
    std::mutex m_mutex;
    std::list<std::pair<Key, std::shared_ptr<const M::Measuring>>> m_entries;
};

MeasuringCache& cache()
{
    static MeasuringCache instance;
    return instance;
}

// A pick on the model: the feature found there in world coordinates, and the
// placement of what it lies on.
struct Resolved {
    M::SurfaceFeature feature{Vec3d::Zero()};
    std::shared_ptr<const M::Measuring> measuring;
    Transform3d world = Transform3d::Identity();    // the volume's mesh to world
    const Slic3r::ModelVolume* volume = nullptr;
};

std::optional<Resolved> resolve(const Slic3r::Model& model, const FeaturePick& pick, FeatureFilter filter)
{
    const Slic3r::ModelObject& object = detail::object_at(model, pick.via.object);
    if (pick.via.instance >= object.instances.size())
        throw std::out_of_range("No such instance on the plate");
    const Slic3r::ModelVolume& volume = detail::volume_at(object, pick.volume);
    Resolved r;
    r.volume = &volume;
    r.world = object.instances[pick.via.instance]->get_matrix() * volume.get_matrix();
    Transform3d base = Transform3d::Identity();
    base.linear() = r.world.linear();
    r.measuring = cache().get(volume, base);
    if (pick.facet < 0 || std::size_t(pick.facet) >= volume.mesh().its.indices.size())
        return std::nullopt;
    const Vec3d hit(pick.hit[0], pick.hit[1], pick.hit[2]);
    auto feature = r.measuring->get_feature(std::size_t(pick.facet), base * hit, r.world * base.inverse(),
                                            filter == FeatureFilter::Planes);
    if (!feature)
        return std::nullopt;
    if (filter == FeatureFilter::PointsAndCircles && feature->get_type() != M::SurfaceFeatureType::Point &&
        feature->get_type() != M::SurfaceFeatureType::Circle)
        return std::nullopt;
    r.feature = *feature;
    return r;
}

void push(std::vector<float>& out, const Vec3d& p)
{
    out.push_back(static_cast<float>(p.x()));
    out.push_back(static_cast<float>(p.y()));
    out.push_back(static_cast<float>(p.z()));
}

MeasuredFeature to_feature(const Resolved& r)
{
    MeasuredFeature out;
    const M::SurfaceFeature& f = r.feature;
    switch (f.get_type()) {
    case M::SurfaceFeatureType::Point:
        out.kind = FeatureKind::Point;
        out.a = to_array(f.get_point());
        break;
    case M::SurfaceFeatureType::Edge: {
        out.kind = FeatureKind::Edge;
        const auto [s, e] = f.get_edge();
        out.a = to_array(s);
        out.b = to_array(e);
        break;
    }
    case M::SurfaceFeatureType::Circle: {
        out.kind = FeatureKind::Circle;
        const auto [center, radius, normal] = f.get_circle();
        out.a = to_array(center);
        out.b = to_array(normal);
        out.radius = radius;
        break;
    }
    case M::SurfaceFeatureType::Plane: {
        out.kind = FeatureKind::Plane;
        const auto [index, normal, point] = f.get_plane();
        out.a = to_array(normal);
        out.b = to_array(point);
        const indexed_triangle_set& its = r.volume->mesh().its;
        for (int facet : r.measuring->get_plane_triangle_indices(index))
            for (int k = 0; k < 3; ++k)
                push(out.plane, r.world * its.vertices[its.indices[facet][k]].cast<double>());
        break;
    }
    case M::SurfaceFeatureType::Undef: break;
    }
    return out;
}

// The point a point or circle feature stands for.
std::optional<Vec3d> anchor_of(const M::SurfaceFeature& f)
{
    if (f.get_type() == M::SurfaceFeatureType::Point)
        return f.get_point();
    if (f.get_type() == M::SurfaceFeatureType::Circle)
        return std::get<0>(f.get_circle());
    return std::nullopt;
}

bool is_plane(const M::SurfaceFeature& f) { return f.get_type() == M::SurfaceFeatureType::Plane; }

AssemblyOptions options_for(const FeaturePick& a, const FeaturePick& b, const Resolved& ra, const Resolved& rb)
{
    AssemblyOptions o;
    o.same_object = a.via.object == b.via.object;
    // Two instances of one object move together: the desktop needs two different volumes.
    o.different_parts = o.same_object ? (a.volume != b.volume && a.via.instance == b.via.instance) : true;
    if (!o.different_parts)
        return o;
    const M::AssemblyAction action = M::get_assembly_action(ra.feature, rb.feature);
    o.parallel = action.can_set_to_parallel;
    o.center_coincidence = action.can_set_to_center_coincidence;
    o.flip_first = action.can_set_feature_1_reverse_rotation && is_plane(rb.feature);
    o.flip_second = action.can_set_feature_2_reverse_rotation;
    o.rotate_around = action.can_around_center_of_faces;
    o.has_parallel_distance = action.has_parallel_distance;
    o.parallel_distance = action.parallel_distance;
    o.angle_deg = action.angle_radian * 180.0 / M_PI;
    o.set_xyz = anchor_of(ra.feature) && anchor_of(rb.feature) && M::can_set_xyz_distance(ra.feature, rb.feature);
    return o;
}

// Moves what `pick` lies on by a world transform applied after its placement:
// its instance, or within one object the volume.
void move(Slic3r::Model& model, const FeaturePick& pick, bool volume_only, const Transform3d& world_change)
{
    Slic3r::ModelObject& object = detail::object_at(model, pick.via.object);
    Slic3r::ModelInstance& instance = *object.instances.at(pick.via.instance);
    if (volume_only) {
        Slic3r::ModelVolume& volume = detail::volume_at(object, pick.volume);
        const Transform3d world = instance.get_matrix() * volume.get_matrix();
        volume.set_transformation(Slic3r::Geometry::Transformation(instance.get_matrix().inverse() * world_change * world));
    } else {
        instance.set_transformation(Slic3r::Geometry::Transformation(world_change * instance.get_matrix()));
    }
    detail::settle_on_bed(object);
}

Transform3d about(const Vec3d& point, const Slic3r::Matrix3d& rotation)
{
    Transform3d t = Transform3d::Identity();
    t.translate(point);
    t.rotate(rotation);
    t.translate(-point);
    return t;
}

Transform3d shift(const Vec3d& d)
{
    Transform3d t = Transform3d::Identity();
    t.translate(d);
    return t;
}

} // namespace

MeasureOutcome measure(const Project& project, const std::vector<FeaturePick>& picks, FeatureFilter filter)
{
    MeasureOutcome out;
    project.inspect([&](const Slic3r::Model& model) {
        std::vector<std::optional<Resolved>> resolved;
        for (const FeaturePick& pick : picks) {
            resolved.push_back(resolve(model, pick, filter));
            if (resolved.back())
                out.features.push_back(to_feature(*resolved.back()));
        }
        if (picks.size() < 2 || !resolved[0] || !resolved[1])
            return;
        const M::MeasurementResult result = M::get_measurement(resolved[0]->feature, resolved[1]->feature, true);
        Measurement m;
        if (result.angle)
            m.angle_deg = result.angle->angle * 180.0 / M_PI;
        if (result.distance_strict) {
            m.distance = result.distance_strict->dist;
            m.from = to_array(result.distance_strict->from);
            m.to = to_array(result.distance_strict->to);
        }
        if (result.distance_infinite)
            m.distance_infinite = result.distance_infinite->dist;
        const auto a = anchor_of(resolved[0]->feature), b = anchor_of(resolved[1]->feature);
        if (a && b)
            m.xyz = to_array(*b - *a);
        out.measurement = m;
        out.assembly = options_for(picks[0], picks[1], *resolved[0], *resolved[1]);
    });
    return out;
}

void assemble(Project& project, const FeaturePick& first, const FeaturePick& second, FeatureFilter filter,
              AssemblyStep step, double value, const std::array<double, 3>& xyz)
{
    project.edit([&](Slic3r::Model& model) {
        auto ra = resolve(model, first, filter);
        auto rb = resolve(model, second, filter);
        if (!ra || !rb)
            throw std::invalid_argument("Pick a feature on each part first");
        const AssemblyOptions o = options_for(first, second, *ra, *rb);
        if (!o.different_parts)
            throw std::invalid_argument("The two features must be on different parts");
        const bool volume_only = o.same_object;
        const auto refuse = [](bool allowed) {
            if (!allowed)
                throw std::invalid_argument("These features cannot be aligned that way");
        };
        switch (step) {
        case AssemblyStep::Parallel:
        case AssemblyStep::CenterCoincidence: {
            refuse(step == AssemblyStep::Parallel ? o.parallel : o.center_coincidence);
            const auto [i1, n1, p1] = ra->feature.get_plane();
            const auto [i2, n2, p2] = rb->feature.get_plane();
            // GLGizmoMeasure::set_to_parallel: the second face turns to face the first.
            Vec3d axis;
            double angle = 0;
            Slic3r::Matrix3d rotation = Slic3r::Matrix3d::Identity();
            if (n2.dot(-n1) < 1 - 1e-9)
                Slic3r::Geometry::rotation_from_two_vectors(n2, -n1, axis, angle, &rotation);
            Transform3d change = about(p2, rotation);
            // set_to_center_coincidence: then onto the first face's center.
            if (step == AssemblyStep::CenterCoincidence)
                change = shift(p1 - p2) * change;
            move(model, second, volume_only, change);
            break;
        }
        case AssemblyStep::FlipFirst:
        case AssemblyStep::FlipSecond: {
            const bool flip_first = step == AssemblyStep::FlipFirst;
            refuse(flip_first ? o.flip_first : o.flip_second);
            const Resolved& r = flip_first ? *ra : *rb;
            const auto [index, normal, center] = r.feature.get_plane();
            // GLGizmoMeasure::set_to_reverse_rotation: half a turn about an axis in the face.
            const Vec3d axis = (M::get_one_point_in_plane(center, normal) - center).normalized();
            move(model, flip_first ? first : second, volume_only,
                 about(center, Slic3r::Matrix3d(Eigen::AngleAxisd(M_PI, axis).toRotationMatrix())));
            break;
        }
        case AssemblyStep::RotateAround: {
            refuse(o.rotate_around);
            const auto [index, normal, center] = rb->feature.get_plane();
            move(model, second, volume_only,
                 about(center, Slic3r::Matrix3d(Eigen::AngleAxisd(value * M_PI / 180.0, normal.normalized()).toRotationMatrix())));
            break;
        }
        case AssemblyStep::ParallelDistance: {
            refuse(o.has_parallel_distance);
            const auto [i1, n1, p1] = ra->feature.get_plane();
            const auto [i2, n2, p2] = rb->feature.get_plane();
            // GLGizmoMeasure::set_parallel_distance.
            Vec3d projected;
            M::get_point_projection_to_plane(p2, p1, n1, projected);
            move(model, second, volume_only, shift(projected + n1 * value - p2));
            break;
        }
        case AssemblyStep::SetXyz: {
            refuse(o.set_xyz);
            const Vec3d a = *anchor_of(ra->feature), b = *anchor_of(rb->feature);
            move(model, second, volume_only, shift(Vec3d(xyz[0], xyz[1], xyz[2]) - (b - a)));
            break;
        }
        }
    });
}

} // namespace orca::engine
