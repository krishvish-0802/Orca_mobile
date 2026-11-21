#include "ConfigBuilder.hpp"

#include <stdexcept>

namespace orca::engine {

using Slic3r::ConfigOptionEnumsGeneric;
using Slic3r::ConfigOptionInts;
using Slic3r::DynamicPrintConfig;

DynamicPrintConfig ConfigBuilder::defaults()
{
    DynamicPrintConfig config = DynamicPrintConfig::full_print_config();
    // The built-in defaults use relative E with Marlin, which Print::validate
    // rejects unless each layer resets E. Every system machine profile does so
    // here (e.g. resources/profiles/Creality/machine/fdm_creality_common.json).
    config.option<Slic3r::ConfigOptionString>("before_layer_change_gcode", true)->value =
        ";BEFORE_LAYER_CHANGE\n;[layer_z]\nG92 E0\n";
    return config;
}

DynamicPrintConfig ConfigBuilder::build(DynamicPrintConfig config, const std::map<std::string, std::string>& overrides,
                                       int used_filaments)
{
    for (const auto& [key, value] : overrides) {
        if (config.def()->get(key) == nullptr)
            throw std::runtime_error("Unknown config option: " + key);
        config.set_deserialize_strict(key, value);
    }

    // One filament on the first extruder, standard-flow nozzle: the defaults the
    // desktop CLI fills in when a single-extruder job does not specify them.
    auto& filament_map = config.option<ConfigOptionInts>("filament_map", true)->values;
    if (filament_map.empty())
        filament_map.assign(1, 1);
    auto* nozzle_volume_type = config.option<ConfigOptionEnumsGeneric>("nozzle_volume_type", true);
    if (nozzle_volume_type->values.empty())
        nozzle_volume_type->values.assign(1, Slic3r::nvtStandard);

    config.normalize_fdm(used_filaments);
    return config;
}

} // namespace orca::engine
