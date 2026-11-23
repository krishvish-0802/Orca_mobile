// The desktop's Emboss and SVG tools (EmbossJob's create_mesh and
// create_volume, GLGizmoSVG's select_shape) without their gizmos.
#include "orca_engine/Emboss.hpp"

#include <boost/nowide/convert.hpp>

#include <cmath>
#include <stdexcept>

#include "MeshToolsCommon.hpp"
#include "libslic3r/Emboss.hpp"
#include "libslic3r/EmbossShape.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/NSVGUtils.hpp"
#include "libslic3r/TriangleMesh.hpp"

namespace orca::engine {

namespace {

using Slic3r::Transform3d;
using Slic3r::Vec3d;

// EmbossJob.cpp: how far a raised shape sinks into the surface, so it joins it.
constexpr double kSafeSurfaceOffset = 0.015;
// SurfaceDrag.hpp: how close to vertical a surface may face before "up" is no longer world up.
constexpr double kUpLimit = 0.9;

Slic3r::ModelVolumeType to_slic3r(VolumeKind kind)
{
    switch (kind) {
    case VolumeKind::ModelPart: return Slic3r::ModelVolumeType::MODEL_PART;
    case VolumeKind::NegativeVolume: return Slic3r::ModelVolumeType::NEGATIVE_VOLUME;
    case VolumeKind::ParameterModifier: return Slic3r::ModelVolumeType::PARAMETER_MODIFIER;
    case VolumeKind::SupportBlocker: return Slic3r::ModelVolumeType::SUPPORT_BLOCKER;
    case VolumeKind::SupportEnforcer: return Slic3r::ModelVolumeType::SUPPORT_ENFORCER;
    default: throw std::invalid_argument("Text can be a part, a negative volume, a modifier or a support volume");
    }
}

// The shape in 2D and the scale that turns its integer coordinates into mm.
Slic3r::EmbossShape shape_of(const EmbossSource& source)
{
    Slic3r::EmbossShape shape;
    shape.projection.depth = source.depth_mm;
    if (!source.svg_path.empty()) {
        // GLGizmoSVG's select_shape, at the default tessellation tolerance (0.1 mm).
        Slic3r::NSVGimage_ptr image = Slic3r::nsvgParseFromFile(source.svg_path);
        if (!image)
            throw std::invalid_argument("This SVG file cannot be read");
        Slic3r::NSVGLineParams params{(0.1 * 0.1) / SCALING_FACTOR / SCALING_FACTOR};
        shape.shapes_with_ids = Slic3r::create_shape_with_ids(*image, params);
        if (shape.shapes_with_ids.empty())
            throw std::invalid_argument("This SVG file has no shapes to emboss");
        // Centered, as text is, so it lands where it was tapped.
        Slic3r::center(shape.shapes_with_ids);
        shape.scale = SCALING_FACTOR;
        if (source.svg_width_mm > 0) {
            const Slic3r::BoundingBox box = Slic3r::get_extents(shape.shapes_with_ids);
            const double width = box.size().x() * SCALING_FACTOR;
            if (width > 0)
                shape.scale *= source.svg_width_mm / width;
        }
        return shape;
    }
    if (source.text.empty())
        throw std::invalid_argument("Type some text first");
    auto font = Slic3r::Emboss::create_font_file(source.font_path.c_str());
    if (!font)
        throw std::invalid_argument("This font cannot be read");
    Slic3r::Emboss::FontFileWithCache cached(std::move(font));
    Slic3r::FontProp prop(float(source.letter_height_mm));
    if (source.char_gap != 0)
        prop.char_gap = int(std::lround(source.char_gap));
    if (source.line_gap != 0)
        prop.line_gap = int(std::lround(source.line_gap));
    if (source.boldness_mm != 0)
        prop.boldness = float(source.boldness_mm);
    if (source.skew != 0)
        prop.skew = float(source.skew);
    shape.shapes_with_ids = Slic3r::Emboss::text2vshapes(cached, boost::nowide::widen(source.text), prop);
    shape.scale = Slic3r::Emboss::get_text_shape_scale(prop, *cached.font_file);
    if (shape.shapes_with_ids.empty())
        throw std::invalid_argument("This font has no shapes for this text");
    return shape;
}

// EmbossJob's try_create_mesh: the shape along Z, from `offset` to `offset + depth`.
Slic3r::TriangleMesh mesh_of(Slic3r::EmbossShape& shape, double offset)
{
    const Slic3r::ExPolygons shapes = Slic3r::union_with_delta(shape, Slic3r::Emboss::UNION_DELTA, Slic3r::Emboss::UNION_MAX_ITERATIN);
    if (shapes.empty())
        throw std::invalid_argument("Nothing to emboss");
    const double scale = shape.scale;
    auto project_z = std::make_unique<Slic3r::Emboss::ProjectZ>(shape.projection.depth / scale);
    const Transform3d tr = Eigen::Translation<double, 3>(0., 0., offset) * Eigen::Scaling(scale);
    Slic3r::Emboss::ProjectTransform project(std::move(project_z), tr);
    Slic3r::TriangleMesh mesh(Slic3r::Emboss::polygons2model(shapes, project));
    if (mesh.empty())
        throw std::invalid_argument("Nothing to emboss");
    return mesh;
}

std::string name_of(const EmbossSource& source)
{
    if (!source.svg_path.empty()) {
        const auto slash = source.svg_path.find_last_of("/\\");
        return slash == std::string::npos ? source.svg_path : source.svg_path.substr(slash + 1);
    }
    std::string name = source.text;
    for (char& c : name)
        if (c == '\n')
            c = ' ';
    return name;
}

void check(const EmbossSource& source)
{
    if (!(source.depth_mm > 0))
        throw std::invalid_argument("The depth must be more than 0 mm");
    if (source.svg_path.empty() && !(source.letter_height_mm > 0))
        throw std::invalid_argument("The letter height must be more than 0 mm");
}

} // namespace

std::array<double, 2> emboss_size(const EmbossSource& source)
{
    check(source);
    Slic3r::EmbossShape shape = shape_of(source);
    const Slic3r::BoundingBox box = Slic3r::get_extents(shape.shapes_with_ids);
    return {box.size().x() * shape.scale, box.size().y() * shape.scale};
}

std::size_t emboss_on_surface(Project& project, const FeaturePick& at, VolumeKind kind, const EmbossSource& source)
{
    check(source);
    const Slic3r::ModelVolumeType type = to_slic3r(kind);
    Slic3r::EmbossShape shape = shape_of(source);
    // A part stands out of the surface; anything else cuts into it.
    const bool outside = type == Slic3r::ModelVolumeType::MODEL_PART;
    Slic3r::TriangleMesh mesh = mesh_of(shape, outside ? -kSafeSurfaceOffset : kSafeSurfaceOffset - source.depth_mm);
    std::size_t index = 0;
    project.edit([&](Slic3r::Model& model) {
        Slic3r::ModelObject& object = detail::object_at(model, at.via.object);
        if (at.via.instance >= object.instances.size())
            throw std::out_of_range("No such instance on the plate");
        const Slic3r::ModelVolume& hit_volume = detail::volume_at(object, at.volume);
        const indexed_triangle_set& its = hit_volume.mesh().its;
        if (at.facet < 0 || std::size_t(at.facet) >= its.indices.size())
            throw std::out_of_range("No such triangle on the part");
        // Where it was tapped, and the face's outward normal, in world coordinates.
        const Transform3d instance = object.instances[at.via.instance]->get_matrix();
        const Transform3d world = instance * hit_volume.get_matrix();
        const auto& f = its.indices[at.facet];
        const Vec3d a = world * its.vertices[f[0]].cast<double>();
        const Vec3d b = world * its.vertices[f[1]].cast<double>();
        const Vec3d c = world * its.vertices[f[2]].cast<double>();
        Vec3d normal = (b - a).cross(c - a);
        if (world.linear().determinant() < 0)
            normal = -normal;
        if (normal.norm() < 1e-12)
            throw std::invalid_argument("This spot of the surface has no direction to emboss along");
        normal.normalize();
        const Vec3d position = world * Vec3d(at.hit[0], at.hit[1], at.hit[2]);
        const Transform3d surface = Slic3r::Emboss::create_transformation_onto_surface(position, normal, kUpLimit);

        // EmbossJob's create_volume: the mesh is set after adding, so it is not centered.
        Slic3r::ModelVolume* volume = object.add_volume(Slic3r::make_cube(1., 1., 1.), type);
        volume->set_mesh(std::move(mesh));
        volume->calculate_convex_hull();
        volume->config.set_key_value("extruder", new Slic3r::ConfigOptionInt(0));
        volume->source.is_from_builtin_objects = true;
        volume->name = name_of(source);
        volume->set_transformation(Slic3r::Geometry::Transformation(instance.inverse() * surface));
        volume->set_new_unique_id();
        object.invalidate_bounding_box();
        index = object.volumes.size() - 1;
        if (type == Slic3r::ModelVolumeType::MODEL_PART)
            detail::settle_on_bed(object);
    });
    return index;
}

std::size_t emboss_object(Project& project, const EmbossSource& source, double x, double y)
{
    check(source);
    Slic3r::EmbossShape shape = shape_of(source);
    Slic3r::TriangleMesh mesh = mesh_of(shape, 0.0);
    std::size_t index = 0;
    project.edit([&](Slic3r::Model& model) {
        Slic3r::ModelObject* object = model.add_object();
        object->name = name_of(source);
        Slic3r::ModelVolume* volume = object->add_volume(std::move(mesh));   // centered
        volume->name = object->name;
        volume->source.is_from_builtin_objects = true;
        Slic3r::ModelInstance* instance = object->add_instance();
        instance->set_offset(Vec3d(x, y, 0));
        object->invalidate_bounding_box();
        detail::settle_on_bed(*object);
        index = model.objects.size() - 1;
    });
    return index;
}

} // namespace orca::engine
