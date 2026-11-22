// The desktop's Cut tool (GLGizmoCut3D::perform_cut and its checks), without
// the gizmo: the plane, connectors and options come in a CutRequest.
#include "orca_engine/MeshTools.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>

#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/ClipperUtils.hpp"
#include "libslic3r/CutUtils.hpp"
#include "libslic3r/ExPolygon.hpp"
#include "libslic3r/Geometry.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Tesselate.hpp"
#include "libslic3r/TriangleMesh.hpp"
#include "libslic3r/TriangleMeshSlicer.hpp"

namespace orca::engine {

namespace {

using Slic3r::Transform3d;
using Slic3r::Vec3d;
using Slic3r::Geometry::rotation_transform;
using Slic3r::Geometry::scale_transform;
using Slic3r::Geometry::translation_transform;

double radians(double degrees) { return degrees * M_PI / 180.0; }

std::array<double, 3> to_array(const Vec3d& v) { return {v.x(), v.y(), v.z()}; }

// Where the plane is. The desktop keeps connectors and the cut matrix in the
// instance's frame without its offset ("pos = hit - instance offset").
struct Frame {
    Transform3d rotation = Transform3d::Identity();
    Vec3d center = Vec3d::Zero();      // world
    Vec3d offset = Vec3d::Zero();      // the instance's offset
    Slic3r::BoundingBoxf3 box;         // the instance's parts, world

    Vec3d normal() const { return rotation.linear() * Vec3d::UnitZ(); }
    Vec3d axis(int a) const { return rotation.linear() * Vec3d::Unit(a); }
    // Plane coordinates to the instance's frame without offset (GLGizmoCut3D::get_cut_matrix).
    Transform3d cut_matrix() const { return translation_transform(center - offset) * rotation; }
    Transform3d to_world() const { return translation_transform(center) * rotation; }
    // A point on the plane, mm from its center, in the frame without offset.
    Vec3d at(const std::array<double, 2>& uv) const { return center - offset + axis(0) * uv[0] + axis(1) * uv[1]; }
};

const Slic3r::ModelObject& object_of(const Slic3r::Model& model, const InstanceRef& ref)
{
    if (ref.object >= model.objects.size() || ref.instance >= model.objects[ref.object]->instances.size())
        throw std::out_of_range("No such instance on the plate");
    return *model.objects[ref.object];
}

Frame frame_of(const Slic3r::ModelObject& object, std::size_t instance, const CutPlane& plane)
{
    Frame f;
    f.box = object.instance_bounding_box(instance);
    f.offset = object.instances[instance]->get_offset();
    f.center = Vec3d(f.box.center().x(), f.box.center().y(), plane.height);
    f.rotation = rotation_transform(Vec3d(radians(plane.tilt_x_deg), radians(plane.tilt_y_deg), 0.0));
    return f;
}

// Where the plane meets the parts, less the negative volumes, in plane coordinates (scaled).
Slic3r::ExPolygons section_of(const Slic3r::ModelObject& object, std::size_t instance, const Frame& f)
{
    const Transform3d to_plane = f.cut_matrix().inverse();
    const Transform3d instance_matrix = object.instances[instance]->get_transformation().get_matrix_no_offset();
    Slic3r::ExPolygons parts, holes;
    for (const Slic3r::ModelVolume* volume : object.volumes) {
        if (!volume->is_model_part() && !volume->is_negative_volume())
            continue;
        Slic3r::TriangleMesh mesh(volume->mesh());
        mesh.transform(to_plane * instance_matrix * volume->get_matrix(), true);
        std::vector<Slic3r::ExPolygons> layers = Slic3r::slice_mesh_ex(mesh.its, {0.f});
        if (layers.empty())
            continue;
        Slic3r::ExPolygons& into = volume->is_model_part() ? parts : holes;
        into.insert(into.end(), layers.front().begin(), layers.front().end());
    }
    Slic3r::ExPolygons section = Slic3r::union_ex(parts);
    if (!holes.empty())
        section = Slic3r::diff_ex(section, Slic3r::union_ex(holes));
    return section;
}

bool inside(const Slic3r::ExPolygons& section, double u, double v)
{
    const Slic3r::Point p = Slic3r::Point::new_scale(u, v);
    return std::any_of(section.begin(), section.end(), [&](const Slic3r::ExPolygon& e) { return e.contains(p); });
}

int sectors_of(CutConnectorShape shape)
{
    switch (shape) {
    case CutConnectorShape::Triangle: return 3;
    case CutConnectorShape::Square: return 4;
    case CutConnectorShape::Hexagon: return 6;
    case CutConnectorShape::Circle: return 360;
    }
    return 360;
}

// GLGizmoCut3D::get_connector_mesh: a unit shape, scaled to the connector.
Slic3r::TriangleMesh unit_connector(const CutConnectorSpec& c)
{
    const int sectors = sectors_of(c.shape);
    if (c.type == CutConnectorType::Snap)
        return Slic3r::TriangleMesh(Slic3r::its_make_snap(1.0, 1.0, 0.3f, 0.15f));
    if (c.style == CutConnectorStyle::Prism)
        return Slic3r::TriangleMesh(Slic3r::its_make_cylinder(1.0, 1.0, 2 * M_PI / sectors));
    if (c.type == CutConnectorType::Plug)
        return Slic3r::TriangleMesh(Slic3r::its_make_frustum(1.0, 1.0, 2 * M_PI / sectors));
    return Slic3r::TriangleMesh(Slic3r::its_make_frustum_dowel(1.0, 1.0, sectors));
}

// The connector as GLGizmoCut3D::apply_connectors_in_model and
// apply_cut_connectors place it: a dowel's prism reaches both ways, a plug
// stands on the plane.
struct PlacedConnector {
    Transform3d transform;   // the volume's transformation, in the frame without offset
    double radius = 0;
    double height = 0;
};

PlacedConnector place(const CutConnectorSpec& c, const Frame& f)
{
    PlacedConnector out;
    out.radius = 0.5 * c.size_mm;
    out.height = c.depth_mm;
    Vec3d pos = f.at(c.at);
    if (c.type == CutConnectorType::Dowel) {
        if (c.style == CutConnectorStyle::Prism)
            out.height *= 2;
    } else {
        pos += f.normal() * 0.5 * out.height;
    }
    out.transform = translation_transform(pos) * f.rotation * rotation_transform(-radians(c.rotation_deg) * Vec3d::UnitZ()) *
                    scale_transform(Vec3d(out.radius, out.radius, out.height));
    return out;
}

// GLGizmoCut3D::is_conflict_for_connector.
ConnectorProblem problem_of(std::size_t index, const std::vector<CutConnectorSpec>& connectors,
                            const Slic3r::ExPolygons& section, const Frame& f)
{
    const CutConnectorSpec& c = connectors[index];
    const double r = 0.5 * c.size_mm;
    const int sectors = c.shape == CutConnectorShape::Circle ? 60 : sectors_of(c.shape);
    if (!inside(section, c.at[0], c.at[1]))
        return ConnectorProblem::OutsideSection;
    for (int i = 0; i < sectors; ++i) {
        const double a = 2 * M_PI * i / sectors + M_PI / 2 - radians(c.rotation_deg);
        if (!inside(section, c.at[0] + r * std::cos(a), c.at[1] + r * std::sin(a)))
            return ConnectorProblem::OutsideSection;
    }
    // Its box, from the plane up to its depth, inside the object's.
    Slic3r::BoundingBoxf3 shape(Vec3d(-r, -r, 0), Vec3d(r, r, c.depth_mm));
    const Transform3d matrix = translation_transform(f.at(c.at) + f.offset) * f.rotation;
    const Slic3r::BoundingBoxf3 placed = shape.transformed(matrix);
    const double slack = 1e-3;
    for (int a = 0; a < 3; ++a)
        if (placed.min[a] < f.box.min[a] - slack || placed.max[a] > f.box.max[a] + slack)
            return ConnectorProblem::OutsideObject;
    for (std::size_t i = 0; i < connectors.size(); ++i) {
        if (i == index)
            continue;
        const CutConnectorSpec& other = connectors[i];
        if (std::hypot(other.at[0] - c.at[0], other.at[1] - c.at[1]) < r + 0.5 * other.size_mm)
            return ConnectorProblem::Overlapping;
    }
    return ConnectorProblem::None;
}

void push(std::vector<float>& out, const Vec3d& p)
{
    out.push_back(static_cast<float>(p.x()));
    out.push_back(static_cast<float>(p.y()));
    out.push_back(static_cast<float>(p.z()));
}

double default_dovetail_depth(const Slic3r::BoundingBoxf3& box)
{
    const Vec3d size = box.size();
    return std::max(1.0, 0.5 * (size.x() + size.y() + size.z()) / 30.0);
}

Slic3r::Cut::Groove groove_of(const DovetailSpec& spec, const Slic3r::BoundingBoxf3& box)
{
    Slic3r::Cut::Groove g;
    g.depth = static_cast<float>(spec.depth_mm > 0 ? spec.depth_mm : default_dovetail_depth(box));
    g.width = static_cast<float>(spec.width_mm > 0 ? spec.width_mm : 4.0 * g.depth);
    g.flaps_angle = static_cast<float>(radians(spec.flaps_angle_deg));
    g.angle = static_cast<float>(radians(spec.angle_deg));
    g.depth_tolerance = static_cast<float>(spec.depth_tolerance_mm);
    g.width_tolerance = static_cast<float>(spec.width_tolerance_mm);
    g.depth_init = g.depth;
    g.width_init = g.width;
    g.flaps_angle_init = g.flaps_angle;
    g.angle_init = g.angle;
    return g;
}

// Everything the preview and the cut check, on the model as it is.
struct Checked {
    Frame frame;
    Slic3r::ExPolygons section;
    std::vector<ConnectorProblem> problems;
    bool can_cut = false;
    std::string reason;
};

Checked check(const Slic3r::ModelObject& object, const CutRequest& request)
{
    Checked out;
    out.frame = frame_of(object, request.via.instance, request.plane);
    out.section = section_of(object, request.via.instance, out.frame);
    const bool planar = request.mode == CutMode::Planar;
    if (planar)
        for (std::size_t i = 0; i < request.connectors.size(); ++i)
            out.problems.push_back(problem_of(i, request.connectors, out.section, out.frame));
    const bool has_connectors = planar && !request.connectors.empty();
    if (out.section.empty())
        out.reason = "The plane does not cross the object";
    else if (!has_connectors && request.mode == CutMode::Planar && !request.options.keep_upper && !request.options.keep_lower)
        out.reason = "Keep at least one piece";
    else if (std::any_of(out.problems.begin(), out.problems.end(), [](ConnectorProblem p) { return p != ConnectorProblem::None; }))
        out.reason = "A connector does not fit where it is";
    else if (request.mode == CutMode::Dovetail) {
        // GLGizmoCut3D::has_valid_groove: the flaps must fit in the width.
        const Slic3r::Cut::Groove g = groove_of(request.dovetail, out.frame.box);
        const double flaps_width = -2.0 * g.depth / std::tan(g.flaps_angle);
        if (flaps_width > g.width)
            out.reason = "The dovetail is too narrow for its angle";
    }
    out.can_cut = out.reason.empty();
    return out;
}

} // namespace

CutPreview cut_preview(const Project& project, const CutRequest& request)
{
    CutPreview preview;
    project.inspect([&](const Slic3r::Model& model) {
        const Slic3r::ModelObject& object = object_of(model, request.via);
        const Checked checked = check(object, request);
        const Frame& f = checked.frame;
        preview.box_min = to_array(f.box.min);
        preview.box_max = to_array(f.box.max);
        preview.center = to_array(f.center);
        preview.normal = to_array(f.normal());
        preview.axis_x = to_array(f.axis(0));
        preview.axis_y = to_array(f.axis(1));
        preview.connector_problems = checked.problems;
        preview.can_cut = checked.can_cut;
        preview.reason = checked.reason;
        preview.dovetail_depth_mm = default_dovetail_depth(f.box);

        const Transform3d to_world = f.to_world();
        // The plane: a square a little wider than the object.
        const double half = std::max(5.0, 1.15 * f.box.radius());
        const Vec3d corners[4] = {to_world * Vec3d(-half, -half, 0), to_world * Vec3d(half, -half, 0),
                                  to_world * Vec3d(half, half, 0), to_world * Vec3d(-half, half, 0)};
        for (int i : {0, 1, 2, 0, 2, 3})
            push(preview.plane, corners[i]);

        for (const Vec3d& p : Slic3r::triangulate_expolygons_3d(checked.section, 0.0))
            push(preview.section, to_world * p);
        for (const Slic3r::Polygon& polygon : Slic3r::to_polygons(checked.section)) {
            for (std::size_t i = 0; i < polygon.points.size(); ++i) {
                const Slic3r::Point& a = polygon.points[i];
                const Slic3r::Point& b = polygon.points[(i + 1) % polygon.points.size()];
                push(preview.outline, to_world * Vec3d(Slic3r::unscale<double>(a.x()), Slic3r::unscale<double>(a.y()), 0));
                push(preview.outline, to_world * Vec3d(Slic3r::unscale<double>(b.x()), Slic3r::unscale<double>(b.y()), 0));
            }
        }

        if (request.mode == CutMode::Planar)
            for (const CutConnectorSpec& c : request.connectors) {
                Slic3r::TriangleMesh mesh = unit_connector(c);
                // Centered as ModelObject::add_volume centers a new volume's mesh.
                mesh.translate(-mesh.bounding_box().center().cast<float>());
                const Transform3d placed = translation_transform(f.offset) * place(c, f).transform;
                for (const stl_triangle_vertex_indices& facet : mesh.its.indices)
                    for (int k = 0; k < 3; ++k)
                        push(preview.connectors, placed * mesh.its.vertices[facet[k]].cast<double>());
            }
    });
    return preview;
}

PlanePoint cut_plane_point(const Project& project, const CutRequest& request, const std::array<double, 3>& world)
{
    PlanePoint out;
    project.inspect([&](const Slic3r::Model& model) {
        const Slic3r::ModelObject& object = object_of(model, request.via);
        const Frame f = frame_of(object, request.via.instance, request.plane);
        const Vec3d local = f.to_world().inverse() * Vec3d(world[0], world[1], world[2]);
        out.at = {local.x(), local.y()};
        out.inside_section = inside(section_of(object, request.via.instance, f), local.x(), local.y());
    });
    return out;
}

std::vector<std::size_t> cut(Project& project, const CutRequest& request)
{
    std::vector<std::size_t> created;
    project.edit([&](Slic3r::Model& model) {
        const Slic3r::ModelObject& source = object_of(model, request.via);
        const Checked checked = check(source, request);
        if (!checked.can_cut)
            throw std::invalid_argument(checked.reason);
        const Frame& f = checked.frame;
        const bool planar = request.mode == CutMode::Planar;
        const bool has_connectors = planar && !request.connectors.empty();

        // Work on a copy, so a failure leaves the plate as it was.
        Slic3r::Model scratch;
        Slic3r::ModelObject* work = scratch.add_object(source);
        int dowels = 0;
        int connector_id = 0;
        if (has_connectors)
            for (const CutConnectorSpec& c : request.connectors) {
                const PlacedConnector placed = place(c, f);
                Slic3r::ModelVolume* volume = work->add_volume(Slic3r::TriangleMesh(unit_connector(c)), Slic3r::ModelVolumeType::NEGATIVE_VOLUME);
                volume->set_transformation(placed.transform);
                volume->cut_info = {static_cast<Slic3r::CutConnectorType>(c.type), static_cast<float>(0.5 * c.size_tolerance_mm),
                                    static_cast<float>(c.depth_tolerance_mm)};
                volume->name = "Connector-" + std::to_string(++connector_id);
                if (c.type == CutConnectorType::Dowel)
                    ++dowels;
            }

        using Attr = Slic3r::ModelObjectCutAttribute;
        const CutOptions& o = request.options;
        Slic3r::ModelObjectCutAttributes attributes =
            Slic3r::only_if(has_connectors || o.keep_upper, Attr::KeepUpper) |
            Slic3r::only_if(has_connectors || o.keep_lower, Attr::KeepLower) |
            Slic3r::only_if(!has_connectors && o.as_parts, Attr::KeepAsParts) |
            Slic3r::only_if(o.place_on_cut_upper, Attr::PlaceOnCutUpper) |
            Slic3r::only_if(o.place_on_cut_lower, Attr::PlaceOnCutLower) |
            Slic3r::only_if(o.flip_upper, Attr::FlipUpper) |
            Slic3r::only_if(o.flip_lower, Attr::FlipLower) |
            Slic3r::only_if(dowels > 0, Attr::CreateDowels) |
            Slic3r::only_if(true, Attr::InvalidateCutInfo) | Slic3r::only_if(true, Attr::KeepPaint);

        Slic3r::Cut cutter(work, static_cast<int>(request.via.instance), f.cut_matrix(), attributes);
        const Slic3r::ModelObjectPtrs& pieces =
            planar ? cutter.perform_with_plane()
                   : cutter.perform_with_groove(groove_of(request.dovetail, f.box), f.rotation,
                                                std::max(1, request.dovetail.count), static_cast<float>(request.dovetail.gap_mm),
                                                static_cast<float>(f.box.radius()), o.as_parts);
        if (pieces.empty())
            throw std::invalid_argument("The cut left nothing to keep");

        // Plater::apply_cut_object_to_model: the pieces replace the object.
        const std::size_t before = model.objects.size();
        for (const Slic3r::ModelObject* piece : pieces)
            model.add_object(*piece);
        model.delete_object(request.via.object);
        for (std::size_t i = before - 1; i < model.objects.size(); ++i) {
            Slic3r::ModelObject& piece = *model.objects[i];
            piece.invalidate_bounding_box();
            for (std::size_t k = 0; k < piece.instances.size(); ++k) {
                const double min_z = piece.instance_bounding_box(k).min.z();
                if (std::isfinite(min_z) && min_z != 0.0)
                    piece.translate_instance(k, Vec3d(0, 0, -min_z));
            }
            piece.invalidate_bounding_box();
            created.push_back(i);
        }
    });
    return created;
}

} // namespace orca::engine
