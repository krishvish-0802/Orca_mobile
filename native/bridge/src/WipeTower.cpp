// The prime tower before slicing, as the desktop places and draws it on a plate
// (PartPlate::estimate_wipe_tower_footprint, estimate_wipe_tower_polygon and
// wipe_tower_obstacles), over libslic3r's shared estimate.
#include "WipeTower.hpp"

#include <algorithm>
#include <limits>
#include <set>

#include "ConfigBuilder.hpp"
#include "libslic3r/ClipperUtils.hpp"
#include "libslic3r/GCode/WipeTowerEstimate.hpp"
#include "libslic3r/Geometry.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/libslic3r.h"

namespace orca::engine {

namespace detail {

namespace {

const Slic3r::ConfigOption* option_of(const Slic3r::ModelObject& object, const Slic3r::DynamicPrintConfig& config, const char* key)
{
    const Slic3r::ConfigOption* own = object.config.option(key);
    return own != nullptr ? own : config.option(key);
}

double float_of(const Slic3r::DynamicPrintConfig& config, const char* key, std::size_t index, double fallback)
{
    if (const auto* floats = config.option<Slic3r::ConfigOptionFloats>(key); floats && !floats->values.empty())
        return floats->get_at(index);
    if (const auto* value = config.option<Slic3r::ConfigOptionFloat>(key))
        return value->value;
    return fallback;
}

} // namespace

std::optional<WipeTowerPlacement> place_wipe_tower(const Project& project, const Slic3r::DynamicPrintConfig& config)
{
    const std::vector<InstanceRef> refs = project.plate_instances();
    if (refs.empty())
        return std::nullopt;

    std::set<int> filaments;
    std::set<std::size_t> objects;
    double max_height = 0;
    double layer_height = std::numeric_limits<double>::max();
    Slic3r::Polygons obstacles;
    const double global_layer_height = float_of(config, "layer_height", 0, 0.2);
    project.inspect([&](const Slic3r::Model& model) {
        for (const InstanceRef& ref : refs) {
            const Slic3r::ModelObject& object = *model.objects[ref.object];
            const Slic3r::ModelInstance& instance = *object.instances[ref.instance];
            if (objects.insert(ref.object).second) {
                for (const Slic3r::ModelVolume* volume : object.volumes)
                    for (int id : volume->get_extruders())
                        if (id > 0)
                            filaments.insert(id);
                const Slic3r::ConfigOption* own = object.config.option("layer_height");
                layer_height = std::min(layer_height, own != nullptr ? own->getFloat() : global_layer_height);
            }
            max_height = std::max(max_height, object.instance_convex_hull_bounding_box(ref.instance, true).size().z());
            if (!instance.printable)
                continue;
            Slic3r::Polygon hull = object.convex_hull_2d(instance.get_matrix());
            if (hull.size() < 3)
                continue;
            const Slic3r::ConfigOption* brim_type = option_of(object, config, "brim_type");
            const Slic3r::ConfigOption* brim_width = option_of(object, config, "brim_width");
            const double brim = brim_type && brim_width && brim_type->getInt() != int(Slic3r::btNoBrim) ? brim_width->getFloat() : 0.;
            Slic3r::append(obstacles, Slic3r::offset(hull, float(Slic3r::scaled(brim + Slic3r::WIPE_TOWER_MARGIN))));
        }
    });
    if (layer_height == std::numeric_limits<double>::max())
        layer_height = global_layer_height;

    // The wipe tower filament joins the purges even when nothing else uses it.
    const auto* tower_filament = config.option("wipe_tower_filament");
    const auto* enabled = config.option<Slic3r::ConfigOptionBool>("enable_prime_tower");
    if (enabled && enabled->value && filaments.size() > 1 && tower_filament && tower_filament->getInt() > 0)
        filaments.insert(tower_filament->getInt());
    if (!Slic3r::prime_tower_is_printed(config, int(filaments.size()), int(objects.size()), false))
        return std::nullopt;

    std::vector<unsigned int> ids;
    for (int id : filaments)
        ids.push_back(unsigned(id - 1));
    const Slic3r::WipeTowerType type = Slic3r::resolve_wipe_tower_type(config);
    const Slic3r::WipeTowerFootprint footprint = Slic3r::estimate_wipe_tower_footprint(config, type, ids, layer_height, max_height);
    if (footprint.depth <= 0)
        return std::nullopt;

    WipeTowerPlacement tower;
    tower.width = footprint.width;
    tower.depth = footprint.depth;
    tower.height = footprint.height;
    double brim = footprint.brim_width;
    // A cone wall bulges past the body like a brim.
    const Slic3r::BoundingBox outline =
        Slic3r::get_extents(Slic3r::estimate_wipe_tower_first_layer_outline(config, type, tower.width, tower.depth, tower.height));
    brim += std::max({0., Slic3r::unscaled(outline.max.x()) - tower.width, Slic3r::unscaled(outline.max.y()) - tower.depth,
                      -Slic3r::unscaled(outline.min.x()), -Slic3r::unscaled(outline.min.y())});
    tower.brim = brim;
    if (const auto* rotation = config.option<Slic3r::ConfigOptionFloat>("wipe_tower_rotation_angle"))
        tower.rotation_deg = rotation->value;

    // The saved spot on the bed; far off it, back on with a comfortable margin.
    Slic3r::BoundingBoxf bed;
    if (const auto* area = config.option<Slic3r::ConfigOptionPoints>("printable_area"))
        for (const Slic3r::Vec2d& p : area->values)
            bed.merge(p);
    if (!bed.defined)
        bed = Slic3r::BoundingBoxf(Slic3r::Vec2d(0, 0), Slic3r::Vec2d(220, 220));
    double x = float_of(config, "wipe_tower_x", 0, 15.) ;
    double y = float_of(config, "wipe_tower_y", 0, 220.);
    const double margin = Slic3r::WIPE_TOWER_MARGIN + brim;
    const double lo_x = bed.min.x() + margin, lo_y = bed.min.y() + margin;
    const double hi_x = std::max(lo_x, bed.max.x() - tower.width - margin);
    const double hi_y = std::max(lo_y, bed.max.y() - tower.depth - margin);
    const double comfort = Slic3r::WIPE_TOWER_AUTO_MARGIN + brim;
    double lo_cx = bed.min.x() + comfort, hi_cx = bed.max.x() - tower.width - comfort;
    if (lo_cx > hi_cx) { lo_cx = lo_x; hi_cx = hi_x; }
    double lo_cy = bed.min.y() + comfort, hi_cy = bed.max.y() - tower.depth - comfort;
    if (lo_cy > hi_cy) { lo_cy = lo_y; hi_cy = hi_y; }
    const double tolerance = 5.;
    x = (x < lo_x - tolerance || x > hi_x + tolerance) ? std::clamp(x, lo_cx, hi_cx) : std::clamp(x, lo_x, hi_x);
    y = (y < lo_y - tolerance || y > hi_y + tolerance) ? std::clamp(y, lo_cy, hi_cy) : std::clamp(y, lo_y, hi_y);
    tower.x = x;
    tower.y = y;

    // The footprint, brim included, turned about its corner as the tower turns.
    Slic3r::Polygon footprint_poly({{Slic3r::scaled(-brim), Slic3r::scaled(-brim)},
                                    {Slic3r::scaled(tower.width + brim), Slic3r::scaled(-brim)},
                                    {Slic3r::scaled(tower.width + brim), Slic3r::scaled(tower.depth + brim)},
                                    {Slic3r::scaled(-brim), Slic3r::scaled(tower.depth + brim)}});
    if (tower.rotation_deg != 0.)
        footprint_poly.rotate(Slic3r::Geometry::deg2rad(tower.rotation_deg));
    footprint_poly.translate(Slic3r::scaled(x), Slic3r::scaled(y));
    tower.overlaps_objects = !Slic3r::intersection(Slic3r::Polygons{footprint_poly}, obstacles).empty();
    return tower;
}

} // namespace detail

std::optional<WipeTowerPlacement> wipe_tower(Project& project, const PlateConfig& plate_config, PresetCatalog* catalog)
{
    std::map<std::string, std::string> overrides = plate_config.overrides;
    for (const auto& [key, value] : project.plate_settings())
        overrides[key] = value;
    const Slic3r::DynamicPrintConfig config = ConfigBuilder::resolve(plate_config.presets, overrides, catalog);
    return detail::place_wipe_tower(project, config);
}

} // namespace orca::engine
