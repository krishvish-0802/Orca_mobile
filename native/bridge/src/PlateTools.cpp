// Arrange, auto-orient and fill bed, following the desktop jobs that drive the
// same libslic3r code (src/slic3r/GUI/Jobs/ArrangeJob.cpp, OrientJob.cpp,
// FillBedJob.cpp) minus what needs the GUI: locked plates, the wipe tower and
// Bambu calibration regions. They work on the current plate; Arrange all
// packs every plate, as the desktop's.
#include "orca_engine/PlateTools.hpp"

#include <algorithm>
#include <cmath>
#include <map>
#include <numeric>
#include <stdexcept>
#include <string>
#include <vector>

#include "ConfigBuilder.hpp"
#include "libslic3r/Arrange.hpp"
#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/ClipperUtils.hpp"
#include "libslic3r/Geometry.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/ModelArrange.hpp"
#include "libslic3r/Orient.hpp"
#include "libslic3r/PrintConfig.hpp"

namespace orca::engine {

namespace {

using Slic3r::DynamicPrintConfig;
using Slic3r::scaled;
using Slic3r::arrangement::ArrangeParams;
using Slic3r::arrangement::ArrangePolygon;
using Slic3r::arrangement::ArrangePolygons;

// The slice's config with the current plate's own settings over it.
DynamicPrintConfig resolve(const Project& project, const PlateConfig& config, PresetCatalog* catalog)
{
    std::map<std::string, std::string> overrides = config.overrides;
    for (const auto& [key, value] : project.plate_settings())
        overrides[key] = value;
    return ConfigBuilder::resolve(config.presets, overrides, catalog);
}

double float_option(const DynamicPrintConfig& config, const char* key, double fallback = 0)
{
    const auto* option = config.option<Slic3r::ConfigOptionFloat>(key);
    return option ? option->value : fallback;
}

// init_arrange_params() in ArrangeJob.cpp. Skirts around each object are not
// known before slicing here, so they add no clearance.
ArrangeParams make_params(const DynamicPrintConfig& config, const ArrangeOptions& options)
{
    ArrangeParams params;
    params.progressind = [](unsigned, std::string) {};
    params.clearance_height_to_rod = float_option(config, "extruder_clearance_height_to_rod");
    params.clearance_height_to_lid = float_option(config, "extruder_clearance_height_to_lid");
    params.clearance_radius = float_option(config, "extruder_clearance_radius");
    params.printable_height = float_option(config, "printable_height", 256.0);
    params.nozzle_height = float_option(config, "nozzle_height");
    if (const auto* best = config.option<Slic3r::ConfigOptionPoint>("best_object_pos"))
        params.align_center = best->value;
    params.allow_rotations = options.allow_rotation;
    params.align_to_y_axis = options.align_to_y_axis;
    params.min_obj_distance = scaled(std::max(0.0, options.spacing_mm));
    if (const auto* sequence = config.option<Slic3r::ConfigOptionEnum<Slic3r::PrintSequence>>("print_sequence"))
        params.is_seq_print = sequence->value == Slic3r::PrintSequence::ByObject;

    const auto* belt = config.option<Slic3r::ConfigOptionBool>("belt_printer");
    if (belt && belt->value) {
        const auto* axis_option = config.option<Slic3r::ConfigOptionEnum<Slic3r::BeltRotationAxis>>("belt_slice_rotation");
        const auto axis = axis_option ? axis_option->value : Slic3r::BeltRotationAxis::None;
        const double angle = float_option(config, "belt_slice_rotation_angle");
        const bool tilted = (axis == Slic3r::BeltRotationAxis::X || axis == Slic3r::BeltRotationAxis::Y) &&
                            std::abs(angle) > EPSILON;
        params.is_belt = true;
        params.belt_axis = axis == Slic3r::BeltRotationAxis::Y ? 0 : 1;
        params.belt_reversed = tilted && ((axis == Slic3r::BeltRotationAxis::Y) != (angle < 0.));
        params.belt_tilt_slope =
            tilted ? float(1. / std::tan(Slic3r::Geometry::deg2rad(std::clamp(std::abs(angle), 5., 90.)))) : 0.f;
    }
    if (params.is_seq_print) {
        params.bed_shrink_x = BED_SHRINK_SEQ_PRINT;
        params.bed_shrink_y = BED_SHRINK_SEQ_PRINT;
    }
    return params;
}

// The printer's no-go areas (bed_exclude_area, four corners each) as fixed
// obstacles, as PartPlateList::preprocess_exclude_areas adds them.
void add_exclude_areas(const DynamicPrintConfig& config, ArrangePolygons& fixed)
{
    const auto* area = config.option<Slic3r::ConfigOptionPoints>("bed_exclude_area");
    if (area == nullptr)
        return;
    const auto& points = area->values;
    for (std::size_t i = 0; i + 3 < points.size(); i += 4) {
        Slic3r::BoundingBoxf box;
        for (std::size_t k = i; k < i + 4; ++k)
            box.merge(points[k]);
        if (box.size().x() <= 0 || box.size().y() <= 0)
            continue;
        ArrangePolygon blocker;
        blocker.poly.contour = Slic3r::Polygon({{scaled(box.min.x()), scaled(box.min.y())},
                                                {scaled(box.max.x()), scaled(box.min.y())},
                                                {scaled(box.max.x()), scaled(box.max.y())},
                                                {scaled(box.min.x()), scaled(box.max.y())}});
        blocker.is_virt_object = true;
        blocker.bed_idx = 0;
        blocker.height = 1;
        blocker.name = "ExcludedRegion" + std::to_string(i / 4);
        fixed.push_back(std::move(blocker));
    }
}

// Where an item packed onto logical bed `bed_idx` goes: that many bed widths to
// the right, where the desktop would put its next plates.
coord_t bed_stride(const Slic3r::Points& bed)
{
    return coord_t(Slic3r::BoundingBox(bed).size().x() * 1.2);
}

void place(Slic3r::ModelInstance& instance, const ArrangePolygon& item, coord_t stride)
{
    Slic3r::Vec2crd offset = item.translation;
    offset.x() += stride * item.bed_idx;
    instance.apply_arrange_result(offset.cast<double>(), item.rotation);
}

// OrientJob's setMinimalSupportAreaPrams().
void minimal_support_params(Slic3r::orientation::OrientParams& out)
{
    out.TAR_A = 0.015f;
    out.TAR_B = 0.177f;
    out.RELATIVE_F = 20;
    out.CONTOUR_F = 0.5f;
    out.BOTTOM_F = 2.5f;
    out.BOTTOM_HULL_F = 0.1f;
    out.TAR_C = 0.1f;
    out.TAR_D = 1;
    out.TAR_E = 0.0115f;
    out.FIRST_LAY_H = 0.2f;
    out.VECTOR_TOL = -0.00083f;
    out.NEGL_FACE_SIZE = 0.01f;
    out.ASCENT = -0.5f;
    out.PLAFOND_ADV = 0.0599f;
    out.CONTOUR_AMOUNT = 0.0182427f;
    out.OV_H = 2.574f;
    out.height_offset = 2.3728f;
    out.height_log = 0.041375f;
    out.height_log_k = 1.9325457f;
    out.LAF_MAX = 0.999f;
    out.LAF_MIN = 0.97f;
    out.TAR_LAF = 0.001f;
    out.TAR_PROJ_AREA = 0.1f;
    out.BOTTOM_MIN = 0.1f;
    out.BOTTOM_MAX = 2000;
    out.height_to_bottom_hull_ratio_MIN = 1;
    out.BOTTOM_HULL_MAX = 2000;
    out.APPERANCE_FACE_SUPP = 3;
    out.overhang_angle = 60.f;
    out.use_low_angle_face = true;
    out.min_volume = false;
    out.fun_dir = {};
    out.parallel = true;
}

// OrientJob::get_orient_mesh().
Slic3r::orientation::OrientMesh orient_mesh(Slic3r::ModelInstance* instance, const DynamicPrintConfig& config)
{
    Slic3r::orientation::OrientMesh om;
    Slic3r::ModelObject* object = instance->get_object();
    om.name = object->name;
    om.mesh = object->mesh();
    if (object->config.has("support_threshold_angle"))
        om.overhang_angle = object->config.opt_int("support_threshold_angle");
    else if (config.has("support_threshold_angle"))
        om.overhang_angle = config.opt_int("support_threshold_angle");

    const auto* fan = config.option<Slic3r::ConfigOptionEnum<Slic3r::FanDirection>>("fan_direction");
    const auto* auxiliary = config.option<Slic3r::ConfigOptionBool>("auxiliary_fan");
    if (fan && auxiliary && fan->value != Slic3r::FanDirection::fdUndefine && auxiliary->value) {
        om.cooling_direction = fan->value == Slic3r::FanDirection::fdRight ? Slic3r::Vec3d(1, 0, 0) : Slic3r::Vec3d(-1, 0, 0);
        om.has_cooling_fan = true;
    }
    om.setter = [instance](const Slic3r::orientation::OrientMesh& result) {
        instance->rotate(result.rotation_matrix);
        instance->rotate(result.rotation_matrix_vertical);
        instance->get_object()->invalidate_bounding_box();
        instance->get_object()->ensure_on_bed();
    };
    return om;
}

void check(const Slic3r::Model& model, const InstanceRef& ref)
{
    if (ref.object >= model.objects.size() || ref.instance >= model.objects[ref.object]->instances.size())
        throw std::out_of_range("No such instance on the plate");
}

} // namespace

PlateChange arrange(Project& project, const PlateConfig& plate_config, const ArrangeOptions& options,
                    PresetCatalog* catalog)
{
    return arrange_plates(project, plate_config, options, catalog, false);
}

PlateChange arrange_plates(Project& project, const PlateConfig& plate_config, const ArrangeOptions& options,
                           PresetCatalog* catalog, bool all_plates)
{
    const DynamicPrintConfig config = resolve(project, plate_config, catalog);
    PlateChange change;
    project.edit_plates([&](Slic3r::Model& model, PlateMembership& plates) {
        ArrangeParams params = make_params(config, options);
        std::vector<Slic3r::ModelInstance*> instances;
        ArrangePolygons selected, fixed;
        for (Slic3r::ModelObject* object : model.objects)
            for (Slic3r::ModelInstance* instance : object->instances) {
                if (!all_plates && plates.plate_of(*instance) != plates.current())
                    continue;
                ArrangePolygon item = Slic3r::get_instance_arrange_poly(instance, config);
                item.bed_idx = 0;
                item.itemid = int(selected.size());
                selected.push_back(std::move(item));
                instances.push_back(instance);
            }
        if (selected.empty())
            return;
        add_exclude_areas(config, fixed);

        Slic3r::arrangement::update_arrange_items(selected, fixed, &config, params);
        const Slic3r::Points bed = Slic3r::arrangement::get_shrink_bedpts(&config, params);
        Slic3r::arrangement::arrange(selected, fixed, bed, params);

        // The nester may hand items back in packing order.
        std::sort(selected.begin(), selected.end(),
                  [](const ArrangePolygon& a, const ArrangePolygon& b) { return a.itemid < b.itemid; });
        const bool nothing_arranged = std::none_of(selected.begin(), selected.end(),
                                                   [](const ArrangePolygon& item) { return item.is_arranged(); });
        if (nothing_arranged) {
            change.not_placed = selected.size();
            return;
        }
        const coord_t stride = bed_stride(bed);
        for (std::size_t i = 0; i < selected.size(); ++i) {
            ArrangePolygon item = selected[i];
            if (item.bed_idx < 0) {          // unpackable: stays where it was
                ++change.not_placed;
                continue;
            }
            if (all_plates) {
                // As the desktop's Arrange all: each bed the packer needed is a plate.
                while (plates.count() <= std::size_t(item.bed_idx))
                    plates.add_plate();
                plates.assign(*instances[i], std::size_t(item.bed_idx));
                item.bed_idx = 0;   // on that plate's own bed
                place(*instances[i], item, stride);
                ++change.placed;
                continue;
            }
            place(*instances[i], item, stride);
            ++(item.bed_idx == 0 ? change.placed : change.not_placed);
        }
    });
    return change;
}

void orient(Project& project, const std::optional<InstanceRef>& only, const PlateConfig& plate_config,
            PresetCatalog* catalog)
{
    const DynamicPrintConfig config = resolve(project, plate_config, catalog);
    project.edit_plates([&](Slic3r::Model& model, PlateMembership& plates) {
        Slic3r::orientation::OrientMeshs selected;
        if (only) {
            check(model, *only);
            selected.push_back(orient_mesh(model.objects[only->object]->instances[only->instance], config));
        } else {
            for (Slic3r::ModelObject* object : model.objects)
                for (Slic3r::ModelInstance* instance : object->instances)
                    if (plates.plate_of(*instance) == plates.current())
                        selected.push_back(orient_mesh(instance, config));
        }
        Slic3r::orientation::OrientParams params;
        minimal_support_params(params);
        // orient() calls both without checking, as the desktop always sets them.
        params.progressind = [](unsigned, std::string) {};
        params.stopcondition = [] { return false; };
        Slic3r::orientation::orient(selected, {}, params);
        for (const auto& mesh : selected)
            mesh.apply();
    });
}

PlateChange add_copies(Project& project, const InstanceRef& source, std::optional<std::size_t> count,
                       const PlateConfig& plate_config, const ArrangeOptions& options, PresetCatalog* catalog)
{
    const DynamicPrintConfig config = resolve(project, plate_config, catalog);
    PlateChange change;
    project.edit_plates([&](Slic3r::Model& model, PlateMembership& plates) {
        check(model, source);
        Slic3r::ModelObject* object = model.objects[source.object];
        Slic3r::ModelInstance* original = object->instances[source.instance];
        ArrangeParams params = make_params(config, options);

        // Everything on the plate stays put; the other plates do not matter here.
        ArrangePolygons fixed;
        for (Slic3r::ModelObject* o : model.objects)
            for (Slic3r::ModelInstance* instance : o->instances) {
                if (plates.plate_of(*instance) != plates.current())
                    continue;
                ArrangePolygon item = Slic3r::get_instance_arrange_poly(instance, config);
                item.bed_idx = 0;
                fixed.push_back(std::move(item));
            }
        add_exclude_areas(config, fixed);

        const ArrangePolygon pattern = Slic3r::get_instance_arrange_poly(original, config);
        std::size_t wanted = count.value_or(0);
        if (!count) {
            // FillBedJob::prepare(): as many as the free area could hold, at best.
            const double square_mm = scaled<double>(1.) * scaled<double>(1.);
            const Slic3r::ExPolygons grown = Slic3r::offset_ex(pattern.poly, float(params.min_obj_distance / 2));
            const double item_area = (grown.empty() ? pattern.poly.area() : grown.front().area()) / square_mm;
            const double used = std::accumulate(fixed.begin(), fixed.end(), 0.0,
                [](double sum, const ArrangePolygon& item) { return sum + item.poly.area(); }) / square_mm;
            const double bed_area = std::abs(Slic3r::Polygon(Slic3r::get_bed_shape(config)).area()) / square_mm;
            if (item_area > 0)
                wanted = std::size_t(std::max(0.0, (bed_area - used) / item_area));
            wanted = std::min(wanted, kMaxFillCopies);
        }
        if (wanted == 0)
            return;

        ArrangePolygons copies(wanted, pattern);
        for (std::size_t i = 0; i < copies.size(); ++i) {
            copies[i].bed_idx = 0;
            copies[i].itemid = int(i);
        }
        Slic3r::arrangement::update_arrange_items(copies, fixed, &config, params);
        const Slic3r::Points bed = Slic3r::arrangement::get_shrink_bedpts(&config, params);
        // Filling stops at the first copy that needs another bed.
        bool full = false;
        if (!count) {
            params.stopcondition = [&full] { return full; };
            params.on_packed = [&full](const ArrangePolygon& item) { full = full || item.bed_idx > 0; };
        }
        Slic3r::arrangement::arrange(copies, fixed, bed, params);

        // Asked-for copies that need another bed are still added, beside this one;
        // filling keeps only those on the bed.
        const coord_t stride = bed_stride(bed);
        for (const ArrangePolygon& copy : copies) {
            const bool on_bed = copy.bed_idx == 0;
            if (on_bed)
                ++change.placed;
            else
                ++change.not_placed;
            if (copy.bed_idx < 0 || (!count && !on_bed))
                continue;
            Slic3r::ModelInstance* added = object->add_instance(*original);
            plates.assign(*added, plates.current());
            place(*added, copy, stride);
        }
        if (!count)
            change.not_placed = 0;   // filling tries until the bed is full: nothing is "left out"
        object->invalidate_bounding_box();
    });
    return change;
}

} // namespace orca::engine
