#include "orca_engine/Project.hpp"
#include "ProjectImpl.hpp"

#include <cmath>
#include <mutex>
#include <stdexcept>

#include "libslic3r/Geometry.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMesh.hpp"

namespace orca::engine {


namespace {

// Puts the object's origin at the center of its box, as the desktop does on
// import, so rotating and scaling keep it in place. Instances are moved back
// so nothing moves on the bed.
void center_origin(Slic3r::ModelObject& object)
{
    const Slic3r::Vec3d shift = -object.full_raw_mesh_bounding_box().center();
    if (shift.squaredNorm() == 0.0)
        return;
    object.translate(shift);
    object.origin_translation += shift;
    for (Slic3r::ModelInstance* instance : object.instances) {
        const Slic3r::Vec3d moved = instance->get_matrix().linear() * shift;
        instance->set_offset(instance->get_offset() - moved);
    }
    object.invalidate_bounding_box();
}

} // namespace

Project::Project() : m_impl(std::make_unique<Impl>()) {}
Project::~Project() = default;

std::unique_ptr<Project> Project::load(const std::string& path)
{
    using Slic3r::LoadStrategy;
    std::unique_ptr<Project> project(new Project());
    project->m_impl->source_path = path;
    project->m_impl->model = Slic3r::Model::read_from_file(
        path, nullptr, nullptr, LoadStrategy::AddDefaultInstances | LoadStrategy::LoadModel);
    if (project->m_impl->model.objects.empty())
        throw std::runtime_error("The file contains no printable objects: " + path);
    for (Slic3r::ModelObject* object : project->m_impl->model.objects) {
        center_origin(*object);
        object->ensure_on_bed();
    }
    return project;
}

const std::string& Project::source_path() const { return m_impl->source_path; }

void Project::edit(const std::function<void(Slic3r::Model&)>& change)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    change(m_impl->model);
}

void Project::remove(const InstanceRef& ref)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->instance(ref);   // validates
    Slic3r::ModelObject* object = m_impl->model.objects[ref.object];
    if (object->instances.size() == 1)
        m_impl->model.delete_object(ref.object);
    else
        object->delete_instance(ref.instance);
}

Slic3r::Model Project::model_copy() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    return m_impl->model;
}

std::vector<ObjectSummary> Project::objects() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    std::vector<ObjectSummary> result;
    for (const Slic3r::ModelObject* object : m_impl->model.objects) {
        ObjectSummary summary;
        summary.name = object->name;
        summary.instance_count = object->instances.size();
        for (const Slic3r::ModelVolume* volume : object->volumes)
            if (volume->is_model_part())
                summary.triangle_count += volume->mesh().its.indices.size();
        if (!object->instances.empty()) {
            const Slic3r::Vec3d size = object->instance_bounding_box(0).size();
            summary.size = {size.x(), size.y(), size.z()};
        }
        result.push_back(std::move(summary));
    }
    return result;
}

void Project::center_on(double x, double y)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->model.center_instances_around_point(Slic3r::Vec2d(x, y));
}

std::vector<float> Project::object_triangles(std::size_t object_index) const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    std::vector<float> positions;
    if (object_index >= m_impl->model.objects.size())
        return positions;
    // Model parts with their volume transforms, in object coordinates.
    const indexed_triangle_set its = m_impl->model.objects[object_index]->raw_indexed_triangle_set();
    positions.reserve(its.indices.size() * 9);
    for (const auto& face : its.indices)
        for (int k = 0; k < 3; ++k) {
            const Slic3r::Vec3f& v = its.vertices[face[k]];
            positions.insert(positions.end(), {v.x(), v.y(), v.z()});
        }
    return positions;
}

std::vector<InstanceRef> Project::instances() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    std::vector<InstanceRef> refs;
    for (std::size_t o = 0; o < m_impl->model.objects.size(); ++o)
        for (std::size_t i = 0; i < m_impl->model.objects[o]->instances.size(); ++i)
            refs.push_back({o, i});
    return refs;
}

Placement Project::placement(const InstanceRef& ref) const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    const Slic3r::Transform3d& m = m_impl->instance(ref).get_matrix();
    Placement out{};
    for (int i = 0; i < 16; ++i)
        out[i] = m.matrix().data()[i];   // Eigen stores column-major
    return out;
}

void Project::translate(const InstanceRef& ref, double dx, double dy)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    Slic3r::ModelInstance& instance = m_impl->instance(ref);
    instance.set_offset(instance.get_offset() + Slic3r::Vec3d(dx, dy, 0.0));
    m_impl->model.objects[ref.object]->invalidate_bounding_box();
}

void Project::rotate_z(const InstanceRef& ref, double radians)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    if (!std::isfinite(radians) || radians == 0.0)
        return;
    Slic3r::ModelObject& object = *m_impl->model.objects.at(ref.object);
    Slic3r::ModelInstance& instance = m_impl->instance(ref);
    const Slic3r::Vec3d center = object.instance_bounding_box(ref.instance).center();
    const Slic3r::Transform3d turn = Slic3r::Geometry::translation_transform(center) *
        Slic3r::Transform3d(Eigen::AngleAxisd(radians, Slic3r::Vec3d::UnitZ())) *
        Slic3r::Geometry::translation_transform(-center);
    instance.set_transformation(Slic3r::Geometry::Transformation(turn * instance.get_matrix()));
    object.invalidate_bounding_box();
}

namespace {

Slic3r::Vec3d world_axis(int axis)
{
    if (axis < 0 || axis > 2)
        throw std::invalid_argument("No such axis");
    return Slic3r::Vec3d::Unit(axis);
}

// Grows `scale` by `factor` along `axis`, or all three for -1; refuses a scale
// that would vanish.
Slic3r::Vec3d grown(Slic3r::Vec3d scale, int axis, double factor)
{
    if (!std::isfinite(factor) || factor <= 0.0)
        throw std::invalid_argument("A scale must be more than 0 %");
    if (axis < -1 || axis > 2)
        throw std::invalid_argument("No such axis");
    for (int a = 0; a < 3; ++a)
        if (axis < 0 || axis == a)
            scale[a] = std::max(scale[a] * factor, 1e-4);
    return scale;
}

} // namespace

void Project::rotate_axis(const InstanceRef& ref, int axis, double radians)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    const Slic3r::Vec3d direction = world_axis(axis);
    if (!std::isfinite(radians) || radians == 0.0)
        return;
    Slic3r::ModelObject& object = *m_impl->model.objects.at(ref.object);
    Slic3r::ModelInstance& instance = m_impl->instance(ref);
    const Slic3r::Vec3d center = object.instance_bounding_box(ref.instance).center();
    const Slic3r::Transform3d turn = Slic3r::Geometry::translation_transform(center) *
        Slic3r::Transform3d(Eigen::AngleAxisd(radians, direction)) * Slic3r::Geometry::translation_transform(-center);
    instance.set_transformation(Slic3r::Geometry::Transformation(turn * instance.get_matrix()));
    m_impl->settle(ref);
}

void Project::scale_by(const InstanceRef& ref, int axis, double factor)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    Slic3r::ModelInstance& instance = m_impl->instance(ref);
    instance.set_scaling_factor(grown(instance.get_scaling_factor(), axis, factor));
    m_impl->settle(ref);
}

InstanceTransform Project::transform(const InstanceRef& ref) const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    const Slic3r::ModelInstance& instance = m_impl->instance(ref);
    const Slic3r::ModelObject& object = *m_impl->model.objects[ref.object];
    InstanceTransform out;
    const Slic3r::Vec3d offset = instance.get_offset();
    const Slic3r::Vec3d rotation = instance.get_rotation();
    const Slic3r::Vec3d scale = instance.get_scaling_factor();
    const Slic3r::Vec3d mirror = instance.get_mirror();
    const Slic3r::Vec3d raw = object.full_raw_mesh_bounding_box().size();
    for (int axis = 0; axis < 3; ++axis) {
        out.position[axis] = offset[axis];
        out.rotation_deg[axis] = Slic3r::Geometry::rad2deg(rotation[axis]);
        out.scale[axis] = scale[axis];
        out.size[axis] = raw[axis] * std::abs(scale[axis]);
        out.mirrored[axis] = mirror[axis] < 0;
    }
    return out;
}

void Project::set_position(const InstanceRef& ref, double x, double y)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    Slic3r::ModelInstance& instance = m_impl->instance(ref);
    if (!std::isfinite(x) || !std::isfinite(y))
        throw std::invalid_argument("The position is not a number");
    instance.set_offset(Slic3r::Vec3d(x, y, instance.get_offset().z()));
    m_impl->model.objects[ref.object]->invalidate_bounding_box();
}

void Project::set_rotation(const InstanceRef& ref, const std::array<double, 3>& degrees)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    Slic3r::ModelInstance& instance = m_impl->instance(ref);
    Slic3r::Vec3d radians;
    for (int axis = 0; axis < 3; ++axis) {
        if (!std::isfinite(degrees[axis]))
            throw std::invalid_argument("The rotation is not a number");
        radians[axis] = Slic3r::Geometry::deg2rad(degrees[axis]);
    }
    instance.set_rotation(radians);
    m_impl->settle(ref);
}

void Project::set_scale(const InstanceRef& ref, const std::array<double, 3>& factors)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    Slic3r::ModelInstance& instance = m_impl->instance(ref);
    for (double factor : factors)
        if (!std::isfinite(factor) || factor <= 0.0)
            throw std::invalid_argument("A scale must be more than 0 %");
    instance.set_scaling_factor(Slic3r::Vec3d(factors[0], factors[1], factors[2]));
    m_impl->settle(ref);
}

void Project::mirror(const InstanceRef& ref, int axis)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    Slic3r::ModelInstance& instance = m_impl->instance(ref);
    if (axis < 0 || axis > 2)
        throw std::invalid_argument("No such axis");
    const auto a = static_cast<Slic3r::Axis>(axis);
    instance.set_mirror(a, -instance.get_mirror(a));
    m_impl->settle(ref);
}

void Project::reset_transform(const InstanceRef& ref)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    Slic3r::ModelInstance& instance = m_impl->instance(ref);
    instance.set_rotation(Slic3r::Vec3d::Zero());
    instance.set_scaling_factor(Slic3r::Vec3d::Ones());
    instance.set_mirror(Slic3r::Vec3d::Ones());
    m_impl->settle(ref);
}

void Project::lay_on_face(const InstanceRef& ref, const std::array<double, 3>& world_normal)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    Slic3r::ModelInstance& instance = m_impl->instance(ref);
    const Slic3r::Vec3d wanted = Slic3r::Vec3d(world_normal[0], world_normal[1], world_normal[2]).normalized();
    if (!wanted.allFinite())
        throw std::invalid_argument("No face direction");

    // The hull's faces are the sides the object can stand on; take the one
    // pointing most like the face under the finger.
    Slic3r::TriangleMesh hull = m_impl->model.objects[ref.object]->mesh().convex_hull_3d();
    hull.transform(instance.get_matrix(), true);   // mirrored: keep the faces outward
    Slic3r::Vec3d best = wanted;
    double best_dot = -2.0;
    for (const auto& face : hull.its.indices) {
        const Slic3r::Vec3d a = hull.its.vertices[face[0]].cast<double>();
        const Slic3r::Vec3d b = hull.its.vertices[face[1]].cast<double>();
        const Slic3r::Vec3d c = hull.its.vertices[face[2]].cast<double>();
        const Slic3r::Vec3d n = (b - a).cross(c - a);
        const double length = n.norm();
        if (length < 1e-9)
            continue;
        const double dot = (n / length).dot(wanted);
        if (dot > best_dot) {
            best_dot = dot;
            best = n / length;
        }
    }
    Slic3r::Vec3d axis;
    double angle = 0;
    Slic3r::Matrix3d rotation = Slic3r::Matrix3d::Identity();
    Slic3r::Geometry::rotation_from_two_vectors(best, -Slic3r::Vec3d::UnitZ(), axis, angle, &rotation);
    if (angle != 0.0)
        instance.rotate(rotation);
    m_impl->settle(ref);
}

} // namespace orca::engine
