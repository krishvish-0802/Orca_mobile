// The desktop's Simplify (GLGizmoSimplify::apply_simplify) and Mesh boolean
// (GLGizmoMeshBoolean::generate_new_volume) without their gizmos.
#include "orca_engine/MeshTools.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <optional>
#include <stdexcept>
#include <utility>

#include "MeshToolsCommon.hpp"
#include "libslic3r/MeshBoolean.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/QuadricEdgeCollapse.hpp"
#include "libslic3r/TriangleMesh.hpp"
#include "libslic3r/TriangleSelector.hpp"

namespace orca::engine {

namespace {

using Slic3r::ModelObject;
using Slic3r::ModelVolume;

// GLGizmoSimplify's detail levels: the largest quadric error allowed.
float max_error_for(int detail)
{
    static constexpr float kErrors[] = {1e-3f, 1e-2f, 0.1f, 0.5f, 1.f};
    return kErrors[std::clamp(detail, 0, 4)];
}

std::vector<std::size_t> volumes_to_simplify(const ModelObject& object, const SimplifyRequest& request)
{
    if (request.volume) {
        detail::volume_at(object, *request.volume);
        return {*request.volume};
    }
    std::vector<std::size_t> parts;
    for (std::size_t i = 0; i < object.volumes.size(); ++i)
        if (object.volumes[i]->is_model_part())
            parts.push_back(i);
    return parts;
}

indexed_triangle_set simplified(const ModelVolume& volume, const SimplifyRequest& request)
{
    indexed_triangle_set its = volume.mesh().its;
    uint32_t count = 0;
    float max_error = std::numeric_limits<float>::max();
    if (request.by_ratio) {
        const double keep = std::clamp(100.0 - request.ratio_percent, 1.0, 100.0) / 100.0;
        count = static_cast<uint32_t>(std::max<double>(4.0, std::round(its.indices.size() * keep)));
    } else {
        max_error = max_error_for(request.detail);
    }
    Slic3r::its_quadric_edge_collapse(its, count, &max_error);
    return its;
}

void push(std::vector<float>& out, const Slic3r::Vec3d& p)
{
    out.push_back(static_cast<float>(p.x()));
    out.push_back(static_cast<float>(p.y()));
    out.push_back(static_cast<float>(p.z()));
}

const char* mcut_operation(BooleanOperation op)
{
    switch (op) {
    case BooleanOperation::Union: return "UNION";
    case BooleanOperation::Difference: return "A_NOT_B";
    case BooleanOperation::Intersection: return "INTERSECTION";
    }
    return "UNION";
}

const char* suffix_for(BooleanOperation op)
{
    switch (op) {
    case BooleanOperation::Union: return "union";
    case BooleanOperation::Difference: return "difference";
    case BooleanOperation::Intersection: return "intersection";
    }
    return "union";
}

// A volume's paint with its mesh in the object's coordinates (VolumeInfo::save_painting).
std::optional<Slic3r::TriangleSelector::SavedPainting> painting_in_object(const ModelVolume& volume)
{
    auto saved = volume.save_painting();
    if (saved)
        saved->mesh.transform(volume.get_matrix());
    return saved;
}

} // namespace

SimplifyOutcome simplify_preview(const Project& project, const SimplifyRequest& request)
{
    SimplifyOutcome out;
    project.inspect([&](const Slic3r::Model& model) {
        const ModelObject& object = detail::object_at(model, request.object);
        if (object.instances.empty())
            return;
        const Slic3r::Transform3d instance = object.instances.front()->get_matrix();
        for (std::size_t index : volumes_to_simplify(object, request)) {
            const ModelVolume& volume = *object.volumes[index];
            const indexed_triangle_set its = simplified(volume, request);
            out.before += volume.mesh().its.indices.size();
            out.after += its.indices.size();
            if (out.after > kMaxWireframeTriangles) {
                out.wireframe.clear();
                continue;
            }
            const Slic3r::Transform3d placed = instance * volume.get_matrix();
            for (const auto& facet : its.indices)
                for (int k = 0; k < 3; ++k) {
                    push(out.wireframe, placed * its.vertices[facet[k]].cast<double>());
                    push(out.wireframe, placed * its.vertices[facet[(k + 1) % 3]].cast<double>());
                }
        }
        if (out.after > kMaxWireframeTriangles)
            out.wireframe.clear();
    });
    return out;
}

SimplifyOutcome simplify(Project& project, const SimplifyRequest& request)
{
    SimplifyOutcome out;
    project.edit([&](Slic3r::Model& model) {
        ModelObject& object = detail::object_at(model, request.object);
        for (std::size_t index : volumes_to_simplify(object, request)) {
            ModelVolume& volume = *object.volumes[index];
            indexed_triangle_set its = simplified(volume, request);
            out.before += volume.mesh().its.indices.size();
            out.after += its.indices.size();
            // GLGizmoSimplify::apply_simplify, keeping the paint.
            const auto saved = volume.save_painting();
            volume.set_mesh(Slic3r::TriangleMesh(std::move(its)));
            volume.restore_painting(saved);
            volume.calculate_convex_hull();
            volume.invalidate_convex_hull_2d();
            volume.set_new_unique_id();
        }
        detail::settle_on_bed(object);
    });
    return out;
}

std::size_t mesh_boolean(Project& project, const BooleanRequest& request)
{
    std::size_t result_index = 0;
    project.edit([&](Slic3r::Model& model) {
        ModelObject& object = detail::object_at(model, request.object);
        ModelVolume& source = detail::volume_at(object, request.source);
        ModelVolume& tool = detail::volume_at(object, request.tool);
        if (request.source == request.tool)
            throw std::invalid_argument("Pick two different parts");

        Slic3r::TriangleMesh a = source.mesh();
        a.transform(source.get_matrix(), true);
        Slic3r::TriangleMesh b = tool.mesh();
        b.transform(tool.get_matrix(), true);
        std::vector<Slic3r::TriangleMesh> results;
        Slic3r::MeshBoolean::mcut::make_boolean(a, b, results, mcut_operation(request.operation));
        if (results.empty() || results.front().empty())
            throw std::invalid_argument("The boolean left nothing: the parts may not overlap, or may not be closed");

        // A difference keeps only the source's paint; the others both.
        std::vector<std::optional<Slic3r::TriangleSelector::SavedPainting>> paintings{painting_in_object(source)};
        if (request.operation != BooleanOperation::Difference)
            paintings.push_back(painting_in_object(tool));

        ModelVolume* made = object.add_volume(std::move(results.front()));   // centered, placed where it is
        for (const auto& painting : paintings)
            made->restore_painting(painting, true);
        made->name = source.name + " - " + suffix_for(request.operation);
        made->set_new_unique_id();
        made->config.apply(source.config);
        made->set_type(source.type());
        made->set_material_id(source.material_id());

        // The result takes the source's place; the tool goes when it is used up.
        const std::size_t last = object.volumes.size() - 1;
        std::swap(object.volumes[request.source], object.volumes[last]);
        object.delete_volume(last);
        result_index = request.source;
        if (request.operation == BooleanOperation::Union || request.delete_tool) {
            object.delete_volume(request.tool);
            if (request.tool < result_index)
                --result_index;
        }
        detail::settle_on_bed(object);
    });
    return result_index;
}

} // namespace orca::engine
