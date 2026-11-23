// The desktop's Brim ears tool (GLGizmoBrimEars) without its gizmo.
#include "orca_engine/BrimEars.hpp"

#include <cmath>
#include <limits>
#include <stdexcept>

#include "MeshToolsCommon.hpp"
#include "libslic3r/Brim.hpp"
#include "libslic3r/BrimEarsPoint.hpp"
#include "libslic3r/ClipperUtils.hpp"
#include "libslic3r/ExPolygon.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMeshSlicer.hpp"

namespace orca::engine {

namespace {

using Slic3r::Transform3d;
using Slic3r::Vec3d;

const Slic3r::ModelInstance& instance_of(const Slic3r::Model& model, const InstanceRef& via)
{
    const Slic3r::ModelObject& object = detail::object_at(model, via.object);
    if (via.instance >= object.instances.size())
        throw std::out_of_range("No such instance on the plate");
    return *object.instances[via.instance];
}

// GLGizmoBrimEars::first_layer_slicer: parts less negative volumes, 0.1 mm up.
Slic3r::ExPolygons first_layer(const Slic3r::ModelObject& object, const Slic3r::ModelInstance& instance)
{
    Slic3r::MeshSlicingParamsEx params;
    params.mode = Slic3r::MeshSlicingParams::SlicingMode::Regular;
    params.closing_radius = 0.1f;
    params.extra_offset = 0.05f;
    params.resolution = 0.01;
    Slic3r::ExPolygons parts, negatives;
    for (const Slic3r::ModelVolume* volume : object.volumes) {
        if (!volume->is_model_part() && !volume->is_negative_volume())
            continue;
        indexed_triangle_set its = volume->mesh().its;
        if (its.indices.empty())
            continue;
        Slic3r::MeshSlicingParamsEx p(params);
        p.trafo = instance.get_matrix() * volume->get_matrix();
        if (p.trafo.linear().determinant() < 0.)
            Slic3r::its_flip_triangles(its);
        Slic3r::ExPolygons sliced = Slic3r::slice_mesh_ex(its, {0.1f}, p).front();
        if (volume->is_model_part())
            parts = Slic3r::union_ex(parts, sliced);
        else
            negatives = Slic3r::union_ex(negatives, sliced);
    }
    return Slic3r::diff_ex(parts, negatives);
}

BrimEar to_ear(const Slic3r::BrimPoint& point, const Transform3d& instance)
{
    const Vec3d world = instance * point.pos.cast<double>();
    return {world.x(), world.y(), point.head_front_radius};
}

} // namespace

std::vector<BrimEar> brim_ears(const Project& project, const InstanceRef& via)
{
    std::vector<BrimEar> out;
    project.inspect([&](const Slic3r::Model& model) {
        const Transform3d instance = instance_of(model, via).get_matrix();
        for (const Slic3r::BrimPoint& point : model.objects[via.object]->brim_points)
            out.push_back(to_ear(point, instance));
    });
    return out;
}

void set_brim_ears(Project& project, const InstanceRef& via, const std::vector<BrimEar>& ears)
{
    project.edit([&](Slic3r::Model& model) {
        const Transform3d inverse = instance_of(model, via).get_matrix().inverse();
        Slic3r::BrimPoints points;
        for (const BrimEar& ear : ears) {
            if (!(ear.radius > 0))
                throw std::invalid_argument("A brim ear needs a size");
            // The desktop places ears just under the bed, so they count for the first layer.
            const Vec3d local = inverse * Vec3d(ear.x, ear.y, -0.0001);
            points.emplace_back(local.cast<float>(), float(ear.radius));
        }
        model.objects[via.object]->brim_points = std::move(points);
    });
}

std::vector<float> first_layer_outline(const Project& project, const InstanceRef& via)
{
    std::vector<float> out;
    project.inspect([&](const Slic3r::Model& model) {
        for (const Slic3r::Polygon& polygon : Slic3r::to_polygons(first_layer(*model.objects[via.object], instance_of(model, via)))) {
            for (std::size_t i = 0; i < polygon.points.size(); ++i) {
                for (const Slic3r::Point& p : {polygon.points[i], polygon.points[(i + 1) % polygon.points.size()]}) {
                    out.push_back(float(Slic3r::unscale<double>(p.x())));
                    out.push_back(float(Slic3r::unscale<double>(p.y())));
                    out.push_back(0.f);
                }
            }
        }
    });
    return out;
}

std::optional<std::array<double, 2>> nearest_on_outline(const Project& project, const InstanceRef& via, double x, double y,
                                                        double max_distance)
{
    std::optional<std::array<double, 2>> best;
    project.inspect([&](const Slic3r::Model& model) {
        const Slic3r::Point at = Slic3r::Point::new_scale(x, y);
        double nearest = std::numeric_limits<double>::max();
        for (const Slic3r::Polygon& polygon : Slic3r::to_polygons(first_layer(*model.objects[via.object], instance_of(model, via)))) {
            const Slic3r::Point p = polygon.point_projection(at);
            const double d = (p - at).cast<double>().norm();
            if (d < nearest) {
                nearest = d;
                best = std::array<double, 2>{Slic3r::unscale<double>(p.x()), Slic3r::unscale<double>(p.y())};
            }
        }
        if (Slic3r::unscale<double>(nearest) > max_distance)
            best.reset();
    });
    return best;
}

std::vector<BrimEar> find_brim_ears(const Project& project, const InstanceRef& via, double max_angle_deg,
                                    double detection_length, double radius)
{
    std::vector<BrimEar> out;
    project.inspect([&](const Slic3r::Model& model) {
        for (const Slic3r::ExPolygon& island : first_layer(*model.objects[via.object], instance_of(model, via))) {
            auto add = [&](const Slic3r::Points& points) {
                for (const Slic3r::Point& p : points)
                    out.push_back({Slic3r::unscale<double>(p.x()), Slic3r::unscale<double>(p.y()), radius});
            };
            add(Slic3r::make_brim_ear_points(island.contour, Slic3r::coord_t(scale_(detection_length)), max_angle_deg, true));
            Slic3r::Polygons holes = island.holes;
            Slic3r::polygons_reverse(holes);
            for (const Slic3r::Polygon& hole : holes)
                add(Slic3r::make_brim_ear_points(hole, Slic3r::coord_t(scale_(detection_length)), max_angle_deg, false));
        }
    });
    return out;
}

} // namespace orca::engine
