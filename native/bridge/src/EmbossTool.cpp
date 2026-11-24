// The desktop's Emboss and SVG tools (EmbossJob's create_mesh and
// create_volume, GLGizmoSVG's select_shape) without their gizmos.
#include "orca_engine/Emboss.hpp"

#include <boost/nowide/convert.hpp>

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <utility>
#include <vector>

#include "MeshToolsCommon.hpp"
#include "libslic3r/CutSurface.hpp"
#include "libslic3r/Emboss.hpp"
#include "libslic3r/EmbossShape.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/NSVGUtils.hpp"
#include "libslic3r/TextConfiguration.hpp"
#include "libslic3r/TriangleMesh.hpp"

namespace orca::engine {

namespace {

using Slic3r::Transform3d;
using Slic3r::Vec3d;

// EmbossJob.cpp: how far a raised shape sinks into the surface, so it joins it.
constexpr double kSafeSurfaceOffset = 0.015;
// SurfaceDrag.hpp: how close to vertical a surface may face before "up" is no longer world up.
constexpr double kUpLimit = 0.9;
// EmbossJob.cpp: how far past the surface the cut for a text that follows it looks.
constexpr float kSafeExtension = 1.0f;

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

// --- A text that follows the surface (EmbossJob's cut_surface_to_its) ---

struct SurfaceSource {
    const Slic3r::TriangleMesh* mesh;
    Transform3d tr;   // volume to object
};

// The object's other parts, which the text follows (EmbossJob's create_sources).
std::vector<SurfaceSource> sources_of(const Slic3r::ModelObject& object, const Slic3r::ModelVolume* text)
{
    std::vector<SurfaceSource> sources;
    for (const Slic3r::ModelVolume* v : object.volumes) {
        if (v == text || !v->is_model_part() || v->mesh().its.indices.empty())
            continue;
        sources.push_back({&v->mesh(), v->get_matrix()});
    }
    return sources;
}

Slic3r::Emboss::OrthoProject projection_for_cut(Transform3d tr, double shape_scale, const std::pair<float, float>& z_range)
{
    const double min_z = z_range.first - kSafeExtension;
    const double max_z = z_range.second + kSafeExtension;
    const Vec3d direction = tr.linear() * Vec3d(0., 0., max_z - min_z);
    tr.translate(Vec3d(0., 0., min_z));
    tr.scale(shape_scale);
    return Slic3r::Emboss::OrthoProject(tr, direction);
}

// The shape cut out of the surfaces under it and raised (or sunk) along the text's
// Z, in the text's own coordinates (`tr`: text to object).
Slic3r::TriangleMesh surface_mesh(Slic3r::EmbossShape& shape, const Transform3d& tr, const std::vector<SurfaceSource>& sources,
                                  bool outside)
{
    const Slic3r::ExPolygons shapes = Slic3r::union_with_delta(shape, Slic3r::Emboss::UNION_DELTA, Slic3r::Emboss::UNION_MAX_ITERATIN);
    if (shapes.empty())
        throw std::invalid_argument("Nothing to emboss");
    const Slic3r::BoundingBox bb = Slic3r::get_extents(shapes);
    const double scale = shape.scale;

    const SurfaceSource* biggest = nullptr;
    std::size_t biggest_count = 0;
    std::vector<std::size_t> to_itss(sources.size(), std::numeric_limits<std::size_t>::max());
    std::vector<indexed_triangle_set> itss;
    for (std::size_t i = 0; i < sources.size(); ++i) {
        const Transform3d cut_tr = sources[i].tr.inverse() * tr;
        indexed_triangle_set its = Slic3r::its_cut_AoI(sources[i].mesh->its, bb, projection_for_cut(cut_tr, scale, {0.f, 1.f}));
        if (its.indices.empty())
            continue;
        if (biggest_count < its.vertices.size()) {
            biggest_count = its.vertices.size();
            biggest = &sources[i];
        }
        to_itss[i] = itss.size();
        itss.push_back(std::move(its));
    }
    if (itss.empty() || biggest == nullptr)
        throw std::invalid_argument("There is no surface under the text to follow");

    const Transform3d tr_inv = biggest->tr.inverse();
    const Transform3d cut_tr = tr_inv * tr;
    Slic3r::BoundingBoxf3 mesh_bb = Slic3r::bounding_box(itss[to_itss[std::size_t(biggest - sources.data())]]);
    for (std::size_t i = 0; i < sources.size(); ++i) {
        if (to_itss[i] == std::numeric_limits<std::size_t>::max() || &sources[i] == biggest)
            continue;
        indexed_triangle_set& its = itss[to_itss[i]];
        ::its_transform(its, Transform3d(sources[i].tr * tr_inv), true);
        mesh_bb.merge(Slic3r::bounding_box(its));
    }
    const Transform3d emboss_tr = cut_tr.inverse();
    const Slic3r::BoundingBoxf3 mesh_bb_tr = mesh_bb.transformed(emboss_tr);
    const std::pair<float, float> z_range{float(mesh_bb_tr.min.z()), float(mesh_bb_tr.max.z())};
    const Slic3r::Emboss::OrthoProject cut_projection = projection_for_cut(cut_tr, scale, z_range);
    const float ratio = (-z_range.first + kSafeExtension) / (z_range.second - z_range.first + 2 * kSafeExtension);

    Slic3r::ExPolygons reversed;
    const Slic3r::ExPolygons* used = &shapes;
    const bool reflected = Slic3r::has_reflection(tr);
    if (reflected) {
        reversed = shapes;
        for (Slic3r::ExPolygon& e : reversed) {
            e.contour.reverse();
            for (Slic3r::Polygon& hole : e.holes)
                hole.reverse();
        }
        used = &reversed;
    }
    Slic3r::SurfaceCut cut = Slic3r::cut_surface(*used, itss, cut_projection, ratio);
    if (reflected) {
        for (Slic3r::SurfaceCut::Contour& c : cut.contours)
            std::reverse(c.begin(), c.end());
        for (auto& t : cut.indices)
            std::swap(t[0], t[1]);
    }
    if (cut.empty())
        throw std::invalid_argument("There is no surface under the text to follow");

    // EmbossJob's create_emboss_projection.
    const float depth = float(shape.projection.depth);
    const float front = outside ? depth : float(kSafeSurfaceOffset);
    const float back = -(outside ? float(kSafeSurfaceOffset) : depth);
    ::its_transform(cut, Transform3d(Transform3d(emboss_tr).pretranslate(Vec3d(0., 0., front))));
    const Slic3r::Emboss::OrthoProject3d projection(Vec3d(0., 0., back - front));
    Slic3r::TriangleMesh mesh(Slic3r::cut2model(cut, projection));
    if (mesh.empty())
        throw std::invalid_argument("There is no surface under the text to follow");
    return mesh;
}

// --- What a volume was embossed from, kept as the desktop keeps it ---

void remember(Slic3r::ModelVolume& volume, const EmbossSource& source, Slic3r::EmbossShape shape, bool use_surface)
{
    shape.projection.depth = source.depth_mm;
    shape.projection.use_surface = use_surface;
    shape.final_shape = {};
    shape.fix_3mf_tr.reset();
    if (!source.svg_path.empty()) {
        Slic3r::EmbossShape::SvgFile file;
        file.path = source.svg_path;
        shape.svg_file = std::move(file);
        volume.text_configuration.reset();
    } else {
        Slic3r::TextConfiguration text;
        text.text = source.text;
        text.style.type = Slic3r::EmbossStyle::Type::file_path;
        text.style.path = source.font_path;
        const auto slash = source.font_path.find_last_of("/\\");
        text.style.name = slash == std::string::npos ? source.font_path : source.font_path.substr(slash + 1);
        text.style.prop = Slic3r::FontProp(float(source.letter_height_mm));
        if (source.char_gap != 0)
            text.style.prop.char_gap = int(std::lround(source.char_gap));
        if (source.line_gap != 0)
            text.style.prop.line_gap = int(std::lround(source.line_gap));
        if (source.boldness_mm != 0)
            text.style.prop.boldness = float(source.boldness_mm);
        if (source.skew != 0)
            text.style.prop.skew = float(source.skew);
        volume.text_configuration = std::move(text);
    }
    volume.emboss_shape = std::move(shape);
}

VolumeKind kind_of(Slic3r::ModelVolumeType type)
{
    switch (type) {
    case Slic3r::ModelVolumeType::NEGATIVE_VOLUME: return VolumeKind::NegativeVolume;
    case Slic3r::ModelVolumeType::PARAMETER_MODIFIER: return VolumeKind::ParameterModifier;
    case Slic3r::ModelVolumeType::SUPPORT_BLOCKER: return VolumeKind::SupportBlocker;
    case Slic3r::ModelVolumeType::SUPPORT_ENFORCER: return VolumeKind::SupportEnforcer;
    default: return VolumeKind::ModelPart;
    }
}

// The volume's mesh made anew, where it stands; `volume_tr` is text to object.
Slic3r::TriangleMesh make_mesh(const Slic3r::ModelObject& object, const Slic3r::ModelVolume* text, Slic3r::EmbossShape& shape,
                               bool outside, bool use_surface, const Transform3d& volume_tr)
{
    if (use_surface) {
        const std::vector<SurfaceSource> sources = sources_of(object, text);
        if (!sources.empty())
            return surface_mesh(shape, volume_tr, sources, outside);
    }
    return mesh_of(shape, outside ? -kSafeSurfaceOffset : kSafeSurfaceOffset - shape.projection.depth);
}

// Where `at` lands, and the surface's outward normal there, in world coordinates.
std::pair<Vec3d, Vec3d> surface_at(const Slic3r::ModelObject& object, const FeaturePick& at)
{
    if (at.via.instance >= object.instances.size())
        throw std::out_of_range("No such instance on the plate");
    const Slic3r::ModelVolume& hit_volume = detail::volume_at(object, at.volume);
    const indexed_triangle_set& its = hit_volume.mesh().its;
    if (at.facet < 0 || std::size_t(at.facet) >= its.indices.size())
        throw std::out_of_range("No such triangle on the part");
    const Transform3d world = object.instances[at.via.instance]->get_matrix() * hit_volume.get_matrix();
    const auto& f = its.indices[at.facet];
    const Vec3d a = world * its.vertices[f[0]].cast<double>();
    const Vec3d b = world * its.vertices[f[1]].cast<double>();
    const Vec3d c = world * its.vertices[f[2]].cast<double>();
    Vec3d normal = (b - a).cross(c - a);
    if (world.linear().determinant() < 0)
        normal = -normal;
    if (normal.norm() < 1e-12)
        throw std::invalid_argument("This spot of the surface has no direction to emboss along");
    return {world * Vec3d(at.hit[0], at.hit[1], at.hit[2]), normal.normalized()};
}

Slic3r::ModelVolume& embossed_at(Slic3r::ModelObject& object, std::size_t index)
{
    Slic3r::ModelVolume& volume = detail::volume_at(object, index);
    if (!volume.emboss_shape && !volume.text_configuration)
        throw std::invalid_argument("This part is not a text or an SVG");
    return volume;
}

} // namespace

std::array<double, 2> emboss_size(const EmbossSource& source)
{
    check(source);
    Slic3r::EmbossShape shape = shape_of(source);
    const Slic3r::BoundingBox box = Slic3r::get_extents(shape.shapes_with_ids);
    return {box.size().x() * shape.scale, box.size().y() * shape.scale};
}

std::size_t emboss_on_surface(Project& project, const FeaturePick& at, VolumeKind kind, const EmbossSource& source,
                              bool use_surface)
{
    check(source);
    const Slic3r::ModelVolumeType type = to_slic3r(kind);
    Slic3r::EmbossShape shape = shape_of(source);
    // A part stands out of the surface; anything else cuts into it.
    const bool outside = type == Slic3r::ModelVolumeType::MODEL_PART;
    std::size_t index = 0;
    project.edit([&](Slic3r::Model& model) {
        Slic3r::ModelObject& object = detail::object_at(model, at.via.object);
        const auto [position, normal] = surface_at(object, at);
        const Transform3d instance = object.instances[at.via.instance]->get_matrix();
        const Transform3d surface = Slic3r::Emboss::create_transformation_onto_surface(position, normal, kUpLimit);
        const Transform3d volume_tr = instance.inverse() * surface;
        Slic3r::TriangleMesh mesh = make_mesh(object, nullptr, shape, outside, use_surface, volume_tr);

        // EmbossJob's create_volume: the mesh is set after adding, so it is not centered.
        Slic3r::ModelVolume* volume = object.add_volume(Slic3r::make_cube(1., 1., 1.), type);
        volume->set_mesh(std::move(mesh));
        volume->calculate_convex_hull();
        volume->config.set_key_value("extruder", new Slic3r::ConfigOptionInt(0));
        volume->source.is_from_builtin_objects = true;
        volume->name = name_of(source);
        volume->set_transformation(Slic3r::Geometry::Transformation(volume_tr));
        remember(*volume, source, std::move(shape), use_surface);
        volume->set_new_unique_id();
        object.invalidate_bounding_box();
        index = object.volumes.size() - 1;
        if (type == Slic3r::ModelVolumeType::MODEL_PART)
            detail::settle_on_bed(object);
    });
    return index;
}

std::optional<EmbossedVolume> embossed(const Project& project, std::size_t object_index, std::size_t volume_index)
{
    std::optional<EmbossedVolume> out;
    project.inspect([&](const Slic3r::Model& model) {
        const Slic3r::ModelObject& object = detail::object_at(model, object_index);
        const Slic3r::ModelVolume& volume = detail::volume_at(object, volume_index);
        if (!volume.emboss_shape && !volume.text_configuration)
            return;
        EmbossedVolume e;
        e.kind = kind_of(volume.type());
        if (volume.emboss_shape) {
            e.source.depth_mm = volume.emboss_shape->projection.depth;
            e.use_surface = volume.emboss_shape->projection.use_surface;
            const Slic3r::BoundingBox box = Slic3r::get_extents(volume.emboss_shape->shapes_with_ids);
            e.size = {box.size().x() * volume.emboss_shape->scale, box.size().y() * volume.emboss_shape->scale};
        }
        if (volume.text_configuration) {
            const Slic3r::TextConfiguration& text = *volume.text_configuration;
            e.source.text = text.text;
            e.source.font_path = text.style.path;
            e.source.letter_height_mm = text.style.prop.size_in_mm;
            e.source.char_gap = text.style.prop.char_gap.value_or(0);
            e.source.line_gap = text.style.prop.line_gap.value_or(0);
            e.source.boldness_mm = text.style.prop.boldness.value_or(0.f);
            e.source.skew = text.style.prop.skew.value_or(0.f);
        } else if (volume.emboss_shape && volume.emboss_shape->svg_file) {
            e.source.svg_path = volume.emboss_shape->svg_file->path;
            e.source.svg_width_mm = e.size[0];
        }
        out = e;
    });
    return out;
}

void emboss_update(Project& project, std::size_t object_index, std::size_t volume_index, VolumeKind kind,
                   const EmbossSource& source, bool use_surface)
{
    check(source);
    const Slic3r::ModelVolumeType type = to_slic3r(kind);
    Slic3r::EmbossShape shape = shape_of(source);
    project.edit([&](Slic3r::Model& model) {
        Slic3r::ModelObject& object = detail::object_at(model, object_index);
        Slic3r::ModelVolume& volume = embossed_at(object, volume_index);
        // A volume loaded from a 3MF has its transformation baked into its vertices.
        Transform3d volume_tr = volume.get_matrix();
        if (volume.emboss_shape && volume.emboss_shape->fix_3mf_tr)
            volume_tr = volume_tr * volume.emboss_shape->fix_3mf_tr->inverse();
        const bool outside = type == Slic3r::ModelVolumeType::MODEL_PART;
        Slic3r::TriangleMesh mesh = make_mesh(object, &volume, shape, outside, use_surface, volume_tr);
        if (volume.type() != type)
            volume.set_type(type);
        volume.set_mesh(std::move(mesh));
        volume.calculate_convex_hull();
        volume.set_transformation(Slic3r::Geometry::Transformation(volume_tr));
        volume.name = name_of(source);
        remember(volume, source, std::move(shape), use_surface);
        volume.set_new_unique_id();
        object.invalidate_bounding_box();
        if (type == Slic3r::ModelVolumeType::MODEL_PART)
            detail::settle_on_bed(object);
    });
}

void emboss_move(Project& project, std::size_t volume_index, const FeaturePick& at)
{
    project.edit([&](Slic3r::Model& model) {
        Slic3r::ModelObject& object = detail::object_at(model, at.via.object);
        Slic3r::ModelVolume& volume = embossed_at(object, volume_index);
        if (at.volume == volume_index)
            throw std::invalid_argument("A text cannot move onto itself");
        const auto [position, normal] = surface_at(object, at);
        const Transform3d instance = object.instances[at.via.instance]->get_matrix();
        const Transform3d current = instance * volume.get_matrix();

        // Its turn about the surface now, against the upright placement at its spot.
        const Vec3d axis_z = current.linear().col(2).normalized();
        const Transform3d upright = Slic3r::Emboss::create_transformation_onto_surface(current.translation(), axis_z, kUpLimit);
        const Vec3d x0 = upright.linear().col(0).normalized();
        const Vec3d x1 = current.linear().col(0).normalized();
        const double twist = std::atan2(axis_z.dot(x0.cross(x1)), x0.dot(x1));
        const Vec3d scale(current.linear().col(0).norm(), current.linear().col(1).norm(), current.linear().col(2).norm());

        const Transform3d placed = Slic3r::Emboss::create_transformation_onto_surface(position, normal, kUpLimit) *
                                   Eigen::AngleAxisd(twist, Vec3d::UnitZ()) * Eigen::Scaling(scale);
        volume.set_transformation(Slic3r::Geometry::Transformation(instance.inverse() * placed));
        object.invalidate_bounding_box();
    });
}

void emboss_rotate(Project& project, std::size_t object_index, std::size_t volume_index, double radians)
{
    project.edit([&](Slic3r::Model& model) {
        Slic3r::ModelObject& object = detail::object_at(model, object_index);
        Slic3r::ModelVolume& volume = embossed_at(object, volume_index);
        const Transform3d turned = volume.get_matrix() * Eigen::AngleAxisd(radians, Vec3d::UnitZ());
        volume.set_transformation(Slic3r::Geometry::Transformation(turned));
        object.invalidate_bounding_box();
    });
}

void emboss_settle(Project& project, std::size_t object_index)
{
    project.edit([&](Slic3r::Model& model) { detail::settle_on_bed(detail::object_at(model, object_index)); });
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
        remember(*volume, source, std::move(shape), false);
        Slic3r::ModelInstance* instance = object->add_instance();
        instance->set_offset(Vec3d(x, y, 0));
        object->invalidate_bounding_box();
        detail::settle_on_bed(*object);
        index = model.objects.size() - 1;
    });
    return index;
}

} // namespace orca::engine
