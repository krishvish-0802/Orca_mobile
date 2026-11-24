// Keeping the model's filament numbers right when filaments come and go: the
// desktop's Plater::on_filaments_delete and ObjectList's filament column.
#include "orca_engine/Project.hpp"
#include "ProjectImpl.hpp"

#include "libslic3r/Model.hpp"

namespace orca::engine {

namespace {

// The filament keys an object, part or height range may set besides "extruder".
constexpr const char* kFilamentKeys[] = {"support_filament", "support_interface_filament", "brim_filament_id"};

// After filament `deleted` (1-based) is gone: that one falls back to `fallback`
// (or is dropped when 0), later ones move down by one.
void renumber(Slic3r::ModelConfig& config, const char* key, int deleted, int fallback)
{
    if (!config.has(key))
        return;
    const int value = config.opt_int(key);
    if (value == deleted) {
        if (fallback > 0)
            config.set_key_value(key, new Slic3r::ConfigOptionInt(fallback));
        else
            config.erase(key);
    } else if (value > deleted) {
        config.set_key_value(key, new Slic3r::ConfigOptionInt(value - 1));
    }
}

// Drops a filament number beyond `count`.
void limit(Slic3r::ModelConfig& config, const char* key, int count)
{
    if (config.has(key) && config.opt_int(key) > count)
        config.erase(key);
}

} // namespace

void Project::remove_filament(std::size_t filament, std::size_t count)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    const int deleted = int(filament) + 1;
    for (Slic3r::ModelObject* object : m_impl->model.objects) {
        // ObjectList::update_filament_values_for_items_when_delete_filament: an
        // object always names its filament; on the deleted one it takes the first.
        if (!object->config.has("extruder"))
            object->config.set_key_value("extruder", new Slic3r::ConfigOptionInt(1));
        renumber(object->config, "extruder", deleted, 1);
        for (const char* key : kFilamentKeys)
            renumber(object->config, key, deleted, 0);
        for (Slic3r::ModelVolume* volume : object->volumes) {
            // Painted colours: the deleted one is unpainted, later ones move down.
            volume->update_extruder_count_when_delete_filament(count, filament + 1, 0);
            renumber(volume->config, "extruder", deleted, 1);
            for (const char* key : kFilamentKeys)
                renumber(volume->config, key, deleted, 0);
        }
        for (auto& [range, config] : object->layer_config_ranges) {
            renumber(config, "extruder", deleted, 1);
            for (const char* key : kFilamentKeys)
                renumber(config, key, deleted, 0);
        }
    }
}

void Project::limit_filaments(std::size_t count)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    const int n = int(count);
    for (Slic3r::ModelObject* object : m_impl->model.objects) {
        limit(object->config, "extruder", n);
        for (const char* key : kFilamentKeys)
            limit(object->config, key, n);
        for (Slic3r::ModelVolume* volume : object->volumes) {
            volume->update_extruder_count(count);
            limit(volume->config, "extruder", n);
            for (const char* key : kFilamentKeys)
                limit(volume->config, key, n);
        }
        for (auto& [range, config] : object->layer_config_ranges) {
            limit(config, "extruder", n);
            for (const char* key : kFilamentKeys)
                limit(config, key, n);
        }
    }
}

} // namespace orca::engine
