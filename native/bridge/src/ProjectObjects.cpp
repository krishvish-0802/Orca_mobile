// An object's composition, as the desktop's object list edits it
// (src/slic3r/GUI/GUI_ObjectList.cpp): its volumes (parts, negative volumes,
// modifiers, support enforcers and blockers, seam modifiers), per-object and
// per-volume settings, and height ranges with settings of their own.
#include <algorithm>
#include <cmath>
#include <filesystem>
#include <iterator>
#include <stdexcept>

#include "ProjectImpl.hpp"
#include "libslic3r/Geometry.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/TriangleMesh.hpp"
#include "orca_engine/Project.hpp"

namespace orca::engine {

namespace {

using Slic3r::ModelObject;
using Slic3r::ModelVolume;
using Slic3r::ModelVolumeType;

ModelVolumeType to_slic3r(VolumeKind kind)
{
    const int value = static_cast<int>(kind);
    if (value < 0 || value > static_cast<int>(ModelVolumeType::PAINTED_MODIFIER))
        throw std::invalid_argument("No such volume type");
    return static_cast<ModelVolumeType>(value);
}

VolumeKind to_kind(ModelVolumeType type)
{
    return type == ModelVolumeType::INVALID ? VolumeKind::ModelPart : static_cast<VolumeKind>(static_cast<int>(type));
}

ModelVolume& volume_of(ModelObject& object, std::size_t volume)
{
    if (volume >= object.volumes.size())
        throw std::out_of_range("No such part in the object");
    return *object.volumes[volume];
}

SettingValues values_of(const Slic3r::ModelConfig& config)
{
    SettingValues values;
    const Slic3r::DynamicPrintConfig& c = config.get();
    for (const std::string& key : c.keys())
        values[key] = c.opt_serialize(key);
    return values;
}

Slic3r::t_layer_config_ranges::iterator range_at(ModelObject& object, std::size_t index)
{
    if (index >= object.layer_config_ranges.size())
        throw std::out_of_range("No such height range in the object");
    return std::next(object.layer_config_ranges.begin(), static_cast<std::ptrdiff_t>(index));
}

// GUI_ObjectList.cpp's primitive_angle_step(): facets within 0.002 mm of the circle.
double angle_step(double radius)
{
    const double step = 2. * std::acos(1. - std::min(1., 0.002 / std::max(radius, 1e-3)));
    return std::clamp(step, 2. * PI / 720., 2. * PI / 16.);
}

// GUI_ObjectList.cpp's create_mesh() at the desktop's default sizes.
Slic3r::TriangleMesh primitive_mesh(PrimitiveShape shape, double side, const Slic3r::BoundingBoxf3& object_box)
{
    switch (shape) {
    case PrimitiveShape::Cube: return Slic3r::TriangleMesh(Slic3r::its_make_cube(side, side, side));
    case PrimitiveShape::Cylinder:
        return Slic3r::TriangleMesh(Slic3r::its_make_cylinder(0.5 * side, side, angle_step(0.5 * side)));
    case PrimitiveShape::Disc:
        return Slic3r::TriangleMesh(Slic3r::its_make_cylinder(0.5 * side, 0.2, angle_step(0.5 * side)));
    case PrimitiveShape::Sphere: return Slic3r::TriangleMesh(Slic3r::its_make_sphere(0.5 * side, angle_step(0.5 * side)));
    case PrimitiveShape::Slab:
        return Slic3r::TriangleMesh(Slic3r::its_make_cube(object_box.size().x() * 1.5, object_box.size().y() * 1.5,
                                                          object_box.size().z() * 0.5));
    case PrimitiveShape::Cone: return Slic3r::TriangleMesh(Slic3r::its_make_cone(0.5 * side, side, angle_step(0.5 * side)));
    case PrimitiveShape::Torus: {
        const double tube = 0.5 * 0.25 * side;
        const double radius = 0.5 * 0.75 * side + tube;
        return Slic3r::TriangleMesh(Slic3r::its_make_torus(radius, tube, angle_step(radius + tube), angle_step(tube)));
    }
    }
    throw std::invalid_argument("No such shape");
}

const char* shape_name(PrimitiveShape shape)
{
    switch (shape) {
    case PrimitiveShape::Cube: return "Cube";
    case PrimitiveShape::Cylinder: return "Cylinder";
    case PrimitiveShape::Sphere: return "Sphere";
    case PrimitiveShape::Slab: return "Slab";
    case PrimitiveShape::Cone: return "Cone";
    case PrimitiveShape::Torus: return "Torus";
    case PrimitiveShape::Disc: return "Disc";
    }
    return "Shape";
}

// load_generic_subobject(): aligned with the bed, at the object's front right
// corner standing on the bed (a slab: through the object, on the bed).
void place_new_volume(ModelObject& object, ModelVolume& volume, bool slab)
{
    const Slic3r::BoundingBoxf3 object_box = object.instance_bounding_box(0);
    const Slic3r::ModelInstance& instance = *object.instances.front();
    const Slic3r::Transform3d no_offset = instance.get_matrix_no_offset();
    volume.set_transformation(Slic3r::Geometry::Transformation(no_offset.inverse()));
    const Slic3r::BoundingBoxf3 box = volume.mesh().bounding_box();
    const Slic3r::Vec3d offset = slab
        ? Slic3r::Vec3d(0., 0., 0.5 * box.size().z() + object_box.min.z() - instance.get_offset().z())
        : Slic3r::Vec3d(object_box.max.x(), object_box.min.y(), object_box.min.z()) + 0.5 * box.size() - instance.get_offset();
    volume.set_offset(no_offset.inverse() * offset);
}

// A part prints with the object's extruder, as the desktop sets it.
void default_extruder(ModelObject& object, ModelVolume& volume)
{
    int extruder = 0;
    if (volume.is_model_part() && object.config.has("extruder"))
        extruder = object.config.opt_int("extruder");
    volume.config.set_key_value("extruder", new Slic3r::ConfigOptionInt(extruder));
}

void check_range(double min_z, double max_z)
{
    if (!std::isfinite(min_z) || !std::isfinite(max_z) || min_z < 0 || max_z <= min_z)
        throw std::invalid_argument("A height range must start at 0 mm or above and end above its start");
}

// Height ranges may touch but not overlap, as in the desktop's layer editor.
void check_free(const ModelObject& object, double min_z, double max_z, const Slic3r::t_layer_height_range* except)
{
    for (const auto& [range, config] : object.layer_config_ranges) {
        if (except != nullptr && range == *except)
            continue;
        if (min_z < range.second - EPSILON && max_z > range.first + EPSILON)
            throw std::invalid_argument("Height ranges cannot overlap");
    }
}

} // namespace

std::vector<std::string> setting_keys(SettingScope scope)
{
    std::vector<std::string> keys = Slic3r::PrintRegionConfig().keys();
    // The filament an object, part or height range prints with (the desktop's
    // filament column); the engine spreads it over the per-feature filaments.
    keys.insert(keys.begin(), "extruder");
    if (scope == SettingScope::Object) {
        const auto object_keys = Slic3r::PrintObjectConfig().keys();
        keys.insert(keys.end(), object_keys.begin(), object_keys.end());
    } else if (scope == SettingScope::LayerRange) {
        keys.insert(keys.begin(), "layer_height");
    }
    std::vector<std::string> unique;
    for (const std::string& key : keys)
        if (std::find(unique.begin(), unique.end(), key) == unique.end())
            unique.push_back(key);
    return unique;
}

ObjectDetails Project::details(std::size_t object_index) const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    const ModelObject& object = m_impl->object(object_index);
    ObjectDetails details;
    details.name = object.name;
    details.settings = values_of(object.config);
    for (const ModelVolume* volume : object.volumes)
        details.volumes.push_back({volume->name, to_kind(volume->type()), volume->mesh().its.indices.size(),
                                   values_of(volume->config), volume->emboss_shape.has_value() || volume->text_configuration.has_value(),
                                   volume->painted_modifier_depth});
    for (const auto& [range, config] : object.layer_config_ranges)
        details.layer_ranges.push_back({range.first, range.second, values_of(config)});
    return details;
}

ObjectMesh Project::object_mesh(std::size_t object_index) const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    const ModelObject& object = m_impl->object(object_index);
    ObjectMesh mesh;
    std::size_t triangles = 0;
    for (int pass = 0; pass < 2; ++pass) {          // model parts first: only they are picked
        for (std::size_t v = 0; v < object.volumes.size(); ++v) {
            const ModelVolume& volume = *object.volumes[v];
            if (volume.is_model_part() != (pass == 0))
                continue;
            // A painted modifier is its part's mesh again: the paint shows it, not a second shell.
            if (volume.is_painted_modifier())
                continue;
            const indexed_triangle_set& its = volume.mesh().its;
            for (const auto& face : its.indices)
                for (int k = 0; k < 3; ++k) {
                    const Slic3r::Vec3f& p = its.vertices[face[k]];
                    mesh.positions.insert(mesh.positions.end(), {p.x(), p.y(), p.z()});
                }
            ObjectMesh::Piece piece{v, to_kind(volume.type()), triangles, its.indices.size(), {}, volume.extruder_id()};
            const Slic3r::Transform3d& matrix = volume.get_matrix();
            for (int i = 0; i < 16; ++i)
                piece.transform[i] = matrix.matrix().data()[i];
            mesh.pieces.push_back(piece);
            triangles += its.indices.size();
        }
    }
    return mesh;
}

void Project::add_primitive(std::size_t object_index, VolumeKind kind, PrimitiveShape shape, double size_mm)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    ModelObject& object = m_impl->object(object_index);
    if (!std::isfinite(size_mm) || size_mm <= 0)
        throw std::invalid_argument("The shape needs a size above 0 mm");
    const Slic3r::BoundingBoxf3 object_box = object.instance_bounding_box(0);
    ModelVolume* volume = object.add_volume(primitive_mesh(shape, size_mm, object_box), to_slic3r(kind));
    place_new_volume(object, *volume, shape == PrimitiveShape::Slab);
    volume->name = std::string("Generic-") + shape_name(shape);
    default_extruder(object, *volume);
    volume->source.is_from_builtin_objects = true;
    m_impl->settle_all(object_index);
}

void Project::add_volume_from_file(std::size_t object_index, VolumeKind kind, const std::string& path)
{
    Slic3r::Model loaded = Slic3r::Model::read_from_file(path, nullptr, nullptr, Slic3r::LoadStrategy::LoadModel);
    if (loaded.objects.empty())
        throw std::runtime_error("The file contains no printable objects: " + path);
    loaded.add_default_instances();
    Slic3r::TriangleMesh mesh = loaded.mesh();

    std::lock_guard<std::mutex> lock(m_impl->mutex);
    ModelObject& object = m_impl->object(object_index);
    ModelVolume* volume = object.add_volume(std::move(mesh), to_slic3r(kind));
    place_new_volume(object, *volume, false);
    volume->name = std::filesystem::path(path).filename().string();
    default_extruder(object, *volume);
    volume->source.input_file = path;
    m_impl->settle_all(object_index);
}

void Project::remove_volume(std::size_t object_index, std::size_t volume_index)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    ModelObject& object = m_impl->object(object_index);
    const ModelVolume& volume = volume_of(object, volume_index);
    if (volume.is_model_part() && object.parts_count() == 1)
        throw std::invalid_argument("An object needs at least one part");
    object.delete_volume(volume_index);
    m_impl->settle_all(object_index);
}

std::size_t Project::split_to_parts(std::size_t object_index, std::size_t volume_index)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    ModelObject& object = m_impl->object(object_index);
    ModelVolume& volume = volume_of(object, volume_index);
    if (!volume.is_model_part())
        throw std::invalid_argument("Only a part can be split");
    // One extruder: every piece keeps the part's own, as the desktop does.
    const std::size_t pieces = volume.split(1, true);
    if (pieces <= 1)
        throw std::invalid_argument("This part is one piece and cannot be split");
    object.invalidate_bounding_box();
    m_impl->settle_all(object_index);
    return pieces;
}

std::vector<std::size_t> Project::split_to_objects(std::size_t object_index)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    Slic3r::Model& model = m_impl->model;
    ModelObject& object = m_impl->object(object_index);
    const std::size_t before = model.objects.size();
    Slic3r::ModelObjectPtrs created;
    object.split(&created, true);
    if (created.size() <= 1) {
        // Nothing came apart: take back what split() added.
        while (model.objects.size() > before)
            model.delete_object(model.objects.size() - 1);
        throw std::invalid_argument("This object is one piece and cannot be split");
    }
    model.delete_object(object_index);
    std::vector<std::size_t> indices;
    for (std::size_t i = before - 1; i < model.objects.size(); ++i) {
        ModelObject& piece = *model.objects[i];
        piece.invalidate_bounding_box();
        for (std::size_t k = 0; k < piece.instances.size(); ++k)
            m_impl->settle({i, k});
        indices.push_back(i);
    }
    return indices;
}

void Project::set_volume_kind(std::size_t object_index, std::size_t volume_index, VolumeKind kind)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    ModelObject& object = m_impl->object(object_index);
    ModelVolume& volume = volume_of(object, volume_index);
    const ModelVolumeType type = to_slic3r(kind);
    if (volume.is_model_part() && type != ModelVolumeType::MODEL_PART && object.parts_count() == 1)
        throw std::invalid_argument("An object needs at least one part");
    volume.set_type(type);
    object.invalidate_bounding_box();
    m_impl->settle_all(object_index);
}

void Project::rename(std::size_t object_index, std::optional<std::size_t> volume_index, const std::string& name)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    ModelObject& object = m_impl->object(object_index);
    if (name.empty())
        throw std::invalid_argument("A name cannot be empty");
    if (volume_index)
        volume_of(object, *volume_index).name = name;
    else
        object.name = name;
}

InstanceTransform Project::volume_transform(std::size_t object_index, std::size_t volume_index) const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    ModelObject& object = m_impl->object(object_index);
    const ModelVolume& volume = volume_of(object, volume_index);
    InstanceTransform out;
    const Slic3r::Vec3d offset = volume.get_offset();
    const Slic3r::Vec3d rotation = volume.get_rotation();
    const Slic3r::Vec3d scale = volume.get_scaling_factor();
    const Slic3r::Vec3d mirror = volume.get_mirror();
    const Slic3r::Vec3d raw = volume.mesh().bounding_box().size();
    for (int axis = 0; axis < 3; ++axis) {
        out.position[axis] = offset[axis];
        out.rotation_deg[axis] = Slic3r::Geometry::rad2deg(rotation[axis]);
        out.scale[axis] = scale[axis];
        out.size[axis] = raw[axis] * std::abs(scale[axis]);
        out.mirrored[axis] = mirror[axis] < 0;
    }
    return out;
}

void Project::set_volume_transform(std::size_t object_index, std::size_t volume_index,
                                   const std::array<double, 3>& position, const std::array<double, 3>& rotation_deg,
                                   const std::array<double, 3>& scale)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    ModelObject& object = m_impl->object(object_index);
    ModelVolume& volume = volume_of(object, volume_index);
    Slic3r::Vec3d radians;
    for (int axis = 0; axis < 3; ++axis) {
        if (!std::isfinite(position[axis]) || !std::isfinite(rotation_deg[axis]))
            throw std::invalid_argument("The placement is not a number");
        if (!std::isfinite(scale[axis]) || scale[axis] <= 0)
            throw std::invalid_argument("A scale must be more than 0 %");
        radians[axis] = Slic3r::Geometry::deg2rad(rotation_deg[axis]);
    }
    volume.set_offset(Slic3r::Vec3d(position[0], position[1], position[2]));
    volume.set_rotation(radians);
    volume.set_scaling_factor(Slic3r::Vec3d(scale[0], scale[1], scale[2]));
    object.invalidate_bounding_box();
    m_impl->settle_all(object_index);
}

std::array<double, 16> Project::volume_matrix(std::size_t object_index, std::size_t volume_index) const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    const Slic3r::Transform3d& matrix = volume_of(m_impl->object(object_index), volume_index).get_matrix();
    std::array<double, 16> out{};
    for (int i = 0; i < 16; ++i)
        out[i] = matrix.matrix().data()[i];
    return out;
}

namespace {

// The instance's rotation and scale, to turn world directions into object ones.
Slic3r::Matrix3d instance_linear(const Slic3r::ModelInstance& instance)
{
    return instance.get_matrix_no_offset().matrix().topLeftCorner<3, 3>();
}

} // namespace

void Project::translate_volume(const InstanceRef& via, std::size_t volume_index, const std::array<double, 3>& world_delta)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    const Slic3r::ModelInstance& instance = m_impl->instance(via);
    ModelObject& object = m_impl->object(via.object);
    ModelVolume& volume = volume_of(object, volume_index);
    const Slic3r::Vec3d delta(world_delta[0], world_delta[1], world_delta[2]);
    if (!delta.allFinite())
        return;
    volume.set_offset(volume.get_offset() + instance_linear(instance).inverse() * delta);
    object.invalidate_bounding_box();
    if (volume.is_model_part())
        m_impl->settle_all(via.object);
}

void Project::rotate_volume_z(const InstanceRef& via, std::size_t volume_index, double radians)
{
    rotate_volume(via, volume_index, 2, radians);
}

void Project::rotate_volume(const InstanceRef& via, std::size_t volume_index, int axis, double radians)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    if (axis < 0 || axis > 2)
        throw std::invalid_argument("No such axis");
    if (!std::isfinite(radians) || radians == 0.0)
        return;
    const Slic3r::ModelInstance& instance = m_impl->instance(via);
    ModelObject& object = m_impl->object(via.object);
    ModelVolume& volume = volume_of(object, volume_index);
    // A turn about a world axis, written in the object's coordinates, about
    // the volume's own center.
    const Slic3r::Matrix3d linear = instance_linear(instance);
    const Slic3r::Matrix3d turn = linear.inverse() * Eigen::AngleAxisd(radians, Slic3r::Vec3d::Unit(axis)).toRotationMatrix() * linear;
    const Slic3r::Vec3d center = volume.get_matrix() * volume.mesh().bounding_box().center();
    Slic3r::Transform3d about = Slic3r::Transform3d::Identity();
    about.translate(center);
    about.linear() = turn;
    about.translate(-center);
    volume.set_transformation(Slic3r::Geometry::Transformation(about * volume.get_matrix()));
    object.invalidate_bounding_box();
    if (volume.is_model_part())
        m_impl->settle_all(via.object);
}

void Project::scale_volume(const InstanceRef& via, std::size_t volume_index, int axis, double factor)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    if (!std::isfinite(factor) || factor <= 0.0)
        throw std::invalid_argument("A scale must be more than 0 %");
    if (axis < -1 || axis > 2)
        throw std::invalid_argument("No such axis");
    m_impl->instance(via);
    ModelObject& object = m_impl->object(via.object);
    ModelVolume& volume = volume_of(object, volume_index);
    // About the volume's center, which its offset places (new volumes are centered).
    const Slic3r::Vec3d before = volume.get_matrix() * volume.mesh().bounding_box().center();
    Slic3r::Vec3d scale = volume.get_scaling_factor();
    for (int a = 0; a < 3; ++a)
        if (axis < 0 || axis == a)
            scale[a] = std::max(scale[a] * factor, 1e-4);
    volume.set_scaling_factor(scale);
    const Slic3r::Vec3d after = volume.get_matrix() * volume.mesh().bounding_box().center();
    volume.set_offset(volume.get_offset() + (before - after));
    object.invalidate_bounding_box();
    if (volume.is_model_part())
        m_impl->settle_all(via.object);
}

std::array<double, 3> Project::volume_center(const InstanceRef& via, std::size_t volume_index) const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    const Slic3r::ModelInstance& instance = m_impl->instance(via);
    const ModelVolume& volume = volume_of(m_impl->object(via.object), volume_index);
    const Slic3r::Vec3d center = instance.get_matrix() * (volume.get_matrix() * volume.mesh().bounding_box().center());
    return {center.x(), center.y(), center.z()};
}

namespace {

SettingScope scope_of(const SettingTarget& target)
{
    if (target.volume && target.layer_range)
        throw std::invalid_argument("A setting belongs to a part or a height range, not both");
    return target.volume ? SettingScope::Volume : target.layer_range ? SettingScope::LayerRange : SettingScope::Object;
}

Slic3r::ModelConfig& config_of(ModelObject& object, const SettingTarget& target)
{
    if (target.volume)
        return volume_of(object, *target.volume).config;
    if (target.layer_range)
        return range_at(object, *target.layer_range)->second;
    return object.config;
}

} // namespace

void Project::set_setting(const SettingTarget& target, const std::string& key, const std::string& value)
{
    const auto keys = setting_keys(scope_of(target));
    if (std::find(keys.begin(), keys.end(), key) == keys.end())
        throw std::invalid_argument("This setting cannot be changed here: " + key);
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    Slic3r::ModelConfig& config = config_of(m_impl->object(target.object), target);
    Slic3r::ConfigSubstitutionContext context(Slic3r::ForwardCompatibilitySubstitutionRule::Disable);
    config.set_deserialize(key, value, context);
}

void Project::set_painted_depth(std::size_t object_index, std::size_t volume_index, double depth_mm)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    ModelVolume& volume = volume_of(m_impl->object(object_index), volume_index);
    if (!volume.is_painted_modifier())
        throw std::invalid_argument("This part is not a painted modifier");
    volume.painted_modifier_depth = float(std::max(0.0, depth_mm));
}

void Project::reset_setting(const SettingTarget& target, const std::string& key)
{
    scope_of(target);
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    Slic3r::ModelConfig& config = config_of(m_impl->object(target.object), target);
    // A height range always keeps a layer height, as in the desktop.
    if (target.layer_range && key == "layer_height")
        throw std::invalid_argument("A height range always has its own layer height");
    config.erase(key);
}

std::size_t Project::add_layer_range(std::size_t object_index, double min_z, double max_z, const std::string& layer_height)
{
    check_range(min_z, max_z);
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    ModelObject& object = m_impl->object(object_index);
    check_free(object, min_z, max_z, nullptr);
    Slic3r::ModelConfig config;
    Slic3r::ConfigSubstitutionContext context(Slic3r::ForwardCompatibilitySubstitutionRule::Disable);
    config.set_deserialize("layer_height", layer_height, context);
    const auto [it, inserted] = object.layer_config_ranges.emplace(Slic3r::t_layer_height_range(min_z, max_z), config);
    return static_cast<std::size_t>(std::distance(object.layer_config_ranges.begin(), it));
}

void Project::set_layer_range(std::size_t object_index, std::size_t range, double min_z, double max_z)
{
    check_range(min_z, max_z);
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    ModelObject& object = m_impl->object(object_index);
    auto it = range_at(object, range);
    const Slic3r::t_layer_height_range current = it->first;
    check_free(object, min_z, max_z, &current);
    auto node = object.layer_config_ranges.extract(it);
    node.key() = Slic3r::t_layer_height_range(min_z, max_z);
    object.layer_config_ranges.insert(std::move(node));
}

void Project::remove_layer_range(std::size_t object_index, std::size_t range)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    ModelObject& object = m_impl->object(object_index);
    object.layer_config_ranges.erase(range_at(object, range));
}

} // namespace orca::engine
