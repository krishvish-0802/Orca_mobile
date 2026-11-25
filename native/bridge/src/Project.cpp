#include "orca_engine/Project.hpp"
#include "ProjectImpl.hpp"

#include <algorithm>
#include <cmath>
#include <map>
#include <mutex>
#include <stdexcept>

#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"
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

namespace {

// PartPlate.hpp's compute_colum_count: plates are laid out in a near-square grid.
int column_count(int count)
{
    const float value = std::sqrt(float(count));
    const float rounded = std::round(value);
    return int(value > rounded ? rounded + 1 : rounded);
}

} // namespace

std::optional<DesktopPlateGrid> desktop_plate_grid(int count, const Slic3r::DynamicPrintConfig& config)
{
    const auto* area = config.option<Slic3r::ConfigOptionPoints>("printable_area");
    if (area == nullptr || area->values.size() < 3)
        return std::nullopt;
    DesktopPlateGrid grid;
    for (const Slic3r::Vec2d& p : area->values)
        grid.bed.merge(p);
    // PartPlateList keeps whole-mm plate sizes, less the bed axes' tip (Bed3D::Axes::DefaultTipRadius).
    grid.width = int(grid.bed.size().x() - 1.25);
    grid.depth = int(grid.bed.size().y() - 1.25);
    if (grid.width <= 0 || grid.depth <= 0)
        return std::nullopt;
    grid.columns = column_count(std::max(count, 1));
    return grid;
}

namespace {

// A desktop project's plates (bbs_3mf's plate data): the desktop lays plates
// out side by side (PartPlateList::compute_origin), each a bed and a fifth
// apart, and an instance is on the plate whose area holds it. Here every plate
// shares the bed: each instance moves back from its plate onto the bed.
void take_plates(Project::Impl& impl, const Slic3r::PlateDataPtrs& plate_data, const Slic3r::DynamicPrintConfig& config)
{
    if (plate_data.size() <= 1)
        return;
    const int count = int(plate_data.size());
    const auto grid = desktop_plate_grid(count, config);
    if (!grid)
        return;
    const Slic3r::BoundingBoxf& bed = grid->bed;
    const int width = grid->width;
    const int depth = grid->depth;
    impl.plates.assign(std::size_t(count), Project::Impl::Plate{});
    for (int i = 0; i < count; ++i) {
        impl.plates[i].name = plate_data[i]->plate_name;
        for (const std::string& key : plate_setting_keys())
            if (plate_data[i]->config.has(key))
                impl.plates[i].settings[key] = plate_data[i]->config.opt_serialize(key);
    }
    const auto origin_of = [&](int i) { return grid->origin(i); };
    // The plates' own lists of (object, instance), as the desktop loads them; where
    // an instance is in none, the plate whose area holds it.
    std::map<std::pair<int, int>, int> listed;
    for (int i = 0; i < count; ++i)
        for (const auto& [object, instance] : plate_data[i]->objects_and_instances)
            listed.emplace(std::make_pair(object, instance), i);
    for (std::size_t o = 0; o < impl.model.objects.size(); ++o) {
        Slic3r::ModelObject* object = impl.model.objects[o];
        for (std::size_t n = 0; n < object->instances.size(); ++n) {
            Slic3r::ModelInstance* instance = object->instances[n];
            int plate = 0;
            if (auto it = listed.find({int(o), int(n)}); it != listed.end()) {
                plate = it->second;
            } else {
                const Slic3r::Vec3d center = object->instance_bounding_box(*instance).center();
                for (int i = 0; i < count; ++i) {
                    const Slic3r::Vec2d at = origin_of(i);
                    if (center.x() >= at.x() + bed.min.x() && center.x() <= at.x() + bed.min.x() + width &&
                        center.y() >= at.y() + bed.min.y() && center.y() <= at.y() + bed.min.y() + depth) {
                        plate = i;
                        break;
                    }
                }
            }
            const Slic3r::Vec2d origin = origin_of(plate);
            instance->set_offset(instance->get_offset() - Slic3r::Vec3d(origin.x(), origin.y(), 0));
            impl.plate_by_instance[instance->id().id] = std::size_t(plate);
        }
    }
}

} // namespace

std::unique_ptr<Project> Project::load(const std::string& path)
{
    using Slic3r::LoadStrategy;
    std::unique_ptr<Project> project(new Project());
    project->m_impl->source_path = path;
    const bool is_3mf = path.size() > 4 && (path.compare(path.size() - 4, 4, ".3mf") == 0 || path.compare(path.size() - 4, 4, ".3MF") == 0);
    bool loaded = false;
    if (is_3mf) {
        // A desktop project: with its config and plates, when they read.
        try {
            Slic3r::DynamicPrintConfig config;
            Slic3r::ConfigSubstitutionContext substitutions(Slic3r::ForwardCompatibilitySubstitutionRule::EnableSilent);
            Slic3r::PlateDataPtrs plate_data;
            project->m_impl->model = Slic3r::Model::read_from_file(
                path, &config, &substitutions, LoadStrategy::AddDefaultInstances | LoadStrategy::LoadModel | LoadStrategy::LoadConfig,
                &plate_data);
            take_plates(*project->m_impl, plate_data, config);
            if (config.has("print_settings_id") || config.has("printer_settings_id"))
                project->m_impl->loaded_settings = config;
            Slic3r::release_PlateData_list(plate_data);
            loaded = true;
        } catch (const std::exception&) {
            project->m_impl->plates.assign(1, Impl::Plate{});
            project->m_impl->plate_by_instance.clear();
        }
    }
    if (!loaded)
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

bool Project::loaded_settings(Slic3r::DynamicPrintConfig& out) const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    if (!m_impl->loaded_settings)
        return false;
    out = *m_impl->loaded_settings;
    return true;
}

void Project::edit(const std::function<void(Slic3r::Model&)>& change)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    change(m_impl->model);
    // A part's mesh or place changed: its painted modifiers follow (the desktop syncs them too).
    for (Slic3r::ModelObject* object : m_impl->model.objects)
        object->sync_painted_modifiers();
}

void Project::inspect(const std::function<void(const Slic3r::Model&)>& look) const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    look(m_impl->model);
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
        for (const Slic3r::ModelInstance* instance : object->instances)
            summary.instance_plates.push_back(m_impl->plate_of(*instance));
        result.push_back(std::move(summary));
    }
    return result;
}

void Project::center_on(double x, double y)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    // Each plate's objects together, as Model::center_instances_around_point does for one.
    for (std::size_t plate = 0; plate < m_impl->plates.size(); ++plate) {
        Slic3r::BoundingBoxf3 box;
        for (const Slic3r::ModelObject* object : m_impl->model.objects)
            for (const Slic3r::ModelInstance* instance : object->instances)
                if (m_impl->plate_of(*instance) == plate)
                    box.merge(object->instance_bounding_box(*instance));
        if (!box.defined)
            continue;
        const Slic3r::Vec3d shift(x - box.center().x(), y - box.center().y(), 0);
        for (Slic3r::ModelObject* object : m_impl->model.objects) {
            for (Slic3r::ModelInstance* instance : object->instances)
                if (m_impl->plate_of(*instance) == plate)
                    instance->set_offset(instance->get_offset() + shift);
            object->invalidate_bounding_box();
        }
    }
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

void Project::rotate_group_z(const std::vector<InstanceRef>& refs, double radians)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    if (!std::isfinite(radians) || radians == 0.0 || refs.empty())
        return;
    Slic3r::BoundingBoxf3 box;
    for (const InstanceRef& ref : refs) {
        m_impl->instance(ref);   // checks it
        box.merge(m_impl->model.objects[ref.object]->instance_bounding_box(ref.instance));
    }
    const Slic3r::Vec3d center = box.center();
    const Slic3r::Transform3d turn = Slic3r::Geometry::translation_transform(center) *
        Slic3r::Transform3d(Eigen::AngleAxisd(radians, Slic3r::Vec3d::UnitZ())) *
        Slic3r::Geometry::translation_transform(-center);
    for (const InstanceRef& ref : refs) {
        Slic3r::ModelInstance& instance = m_impl->instance(ref);
        instance.set_transformation(Slic3r::Geometry::Transformation(turn * instance.get_matrix()));
        m_impl->model.objects[ref.object]->invalidate_bounding_box();
    }
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
