#include "orca_engine/Project.hpp"

#include <cmath>
#include <mutex>
#include <stdexcept>

#include "libslic3r/Geometry.hpp"
#include "libslic3r/Model.hpp"

namespace orca::engine {

struct Project::Impl {
    mutable std::mutex mutex;
    std::string source_path;
    Slic3r::Model model;

    Slic3r::ModelInstance& instance(const InstanceRef& ref) const
    {
        if (ref.object >= model.objects.size() || ref.instance >= model.objects[ref.object]->instances.size())
            throw std::out_of_range("No such instance on the plate");
        return *model.objects[ref.object]->instances[ref.instance];
    }
};

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
    for (Slic3r::ModelObject* object : project->m_impl->model.objects)
        object->ensure_on_bed();
    return project;
}

const std::string& Project::source_path() const { return m_impl->source_path; }

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

} // namespace orca::engine
