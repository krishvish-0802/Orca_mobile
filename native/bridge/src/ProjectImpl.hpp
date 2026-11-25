#pragma once

// The state behind orca_engine::Project, shared by the files that implement it
// (Project.cpp: loading and placement; ProjectObjects.cpp: volumes, settings
// and height ranges).
#include <cmath>
#include <map>
#include <vector>
#include <mutex>
#include <stdexcept>
#include <string>

#include <optional>

#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "orca_engine/Project.hpp"

namespace orca::engine {

// How the desktop lays a project's plates out (PartPlateList::compute_origin):
// side by side in a near-square grid, each a bed and a fifth apart. Here every
// plate shares the bed; a desktop project file has them apart.
struct DesktopPlateGrid {
    Slic3r::BoundingBoxf bed;
    int width = 0;   // whole-mm plate size, as PartPlateList keeps it
    int depth = 0;
    int columns = 1;

    Slic3r::Vec2d origin(int plate) const
    {
        return Slic3r::Vec2d(double(plate % columns) * width * 1.2, -double(plate / columns) * depth * 1.2);
    }
};

// The grid for `count` plates of the bed in `config` (printable_area); none without a bed.
std::optional<DesktopPlateGrid> desktop_plate_grid(int count, const Slic3r::DynamicPrintConfig& config);

struct Project::Impl {
    mutable std::mutex mutex;
    std::string source_path;
    Slic3r::Model model;

    // Plates: every instance is on one, by its ObjectID. One seen for the first
    // time is on the current plate (it was made there: a copy, a cut piece).
    struct Plate {
        std::string name;
        std::map<std::string, std::string> settings;
    };
    std::vector<Plate> plates{Plate{}};
    std::size_t current_plate = 0;
    mutable std::map<std::size_t, std::size_t> plate_by_instance;

    // Undo and redo (the desktop's UndoRedo stack, for the whole project): the
    // project after each change, oldest first; `at` is the one shown. A copy of
    // a model shares its meshes, so a step costs little.
    struct State {
        Slic3r::Model model;
        std::vector<Plate> plates;
        std::size_t current_plate = 0;
        std::map<std::size_t, std::size_t> plate_by_instance;
    };
    std::vector<State> history;
    std::size_t at = 0;

    // The settings a desktop project file was saved with (its project_settings.config), when it had them.
    std::optional<Slic3r::DynamicPrintConfig> loaded_settings;

    std::size_t plate_of(const Slic3r::ModelInstance& instance) const
    {
        auto [it, added] = plate_by_instance.try_emplace(instance.id().id, current_plate);
        if (it->second >= plates.size())
            it->second = current_plate;
        return it->second;
    }

    void check_plate(std::size_t plate) const
    {
        if (plate >= plates.size())
            throw std::out_of_range("No such plate");
    }

    Slic3r::ModelInstance& instance(const InstanceRef& ref) const
    {
        if (ref.object >= model.objects.size() || ref.instance >= model.objects[ref.object]->instances.size())
            throw std::out_of_range("No such instance on the plate");
        return *model.objects[ref.object]->instances[ref.instance];
    }

    Slic3r::ModelObject& object(std::size_t index) const
    {
        if (index >= model.objects.size())
            throw std::out_of_range("No such object on the plate");
        return *model.objects[index];
    }

    // After a change to the instance's transform: back onto the bed (only this
    // instance; its siblings may stand differently).
    void settle(const InstanceRef& ref)
    {
        Slic3r::ModelObject& object = *model.objects[ref.object];
        object.invalidate_bounding_box();
        const double min_z = object.instance_bounding_box(ref.instance).min.z();
        if (std::isfinite(min_z) && min_z != 0.0)
            object.translate_instance(ref.instance, Slic3r::Vec3d(0, 0, -min_z));
        object.invalidate_bounding_box();
    }

    // After a change to the object's shape: every instance back onto the bed.
    void settle_all(std::size_t object_index)
    {
        for (std::size_t i = 0; i < object(object_index).instances.size(); ++i)
            settle({object_index, i});
    }
};

} // namespace orca::engine
