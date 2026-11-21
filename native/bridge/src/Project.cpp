#include "orca_engine/Project.hpp"

#include <algorithm>
#include <limits>
#include <stdexcept>

#include "libslic3r/Model.hpp"

namespace orca::engine {

struct Project::Impl {
    std::string source_path;
    Slic3r::Model model;
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

Slic3r::Model& Project::model() { return m_impl->model; }
const Slic3r::Model& Project::model() const { return m_impl->model; }

std::vector<ObjectSummary> Project::objects() const
{
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
    m_impl->model.center_instances_around_point(Slic3r::Vec2d(x, y));
}

RenderMesh Project::render_mesh() const
{
    RenderMesh mesh;
    constexpr float inf = std::numeric_limits<float>::infinity();
    mesh.min = {inf, inf, inf};
    mesh.max = {-inf, -inf, -inf};

    for (const Slic3r::ModelObject* object : m_impl->model.objects) {
        const indexed_triangle_set its = object->raw_indexed_triangle_set();
        for (const Slic3r::ModelInstance* instance : object->instances) {
            const Slic3r::Transform3d& m = instance->get_matrix();
            // A mirroring transform flips the winding, which flat normals must follow.
            const bool flips = m.linear().determinant() < 0;
            mesh.positions.reserve(mesh.positions.size() + its.indices.size() * 9);
            mesh.normals.reserve(mesh.normals.size() + its.indices.size() * 9);
            for (const auto& face : its.indices) {
                Slic3r::Vec3d v[3];
                for (int k = 0; k < 3; ++k)
                    v[k] = m * its.vertices[face[k]].cast<double>();
                Slic3r::Vec3d n = (v[1] - v[0]).cross(v[2] - v[0]);
                const double length = n.norm();
                n = length > 0 ? Slic3r::Vec3d(n / length) : Slic3r::Vec3d::UnitZ();
                if (flips)
                    n = -n;
                for (int k = 0; k < 3; ++k) {
                    for (int c = 0; c < 3; ++c) {
                        const float value = static_cast<float>(v[k][c]);
                        mesh.positions.push_back(value);
                        mesh.normals.push_back(static_cast<float>(n[c]));
                        mesh.min[c] = std::min(mesh.min[c], value);
                        mesh.max[c] = std::max(mesh.max[c], value);
                    }
                }
            }
        }
    }
    return mesh;
}

} // namespace orca::engine
