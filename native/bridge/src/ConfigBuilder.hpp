#pragma once

#include <map>
#include <string>

#include "libslic3r/PrintConfig.hpp"

namespace orca::engine {

// Builds the full print config for a slice: a base config (composed presets,
// or OrcaSlicer's defaults) with the caller's overrides applied, normalized the
// way the desktop CLI does for a single-plate job.
class ConfigBuilder {
public:
    // OrcaSlicer's built-in defaults, the base when no presets are selected.
    static Slic3r::DynamicPrintConfig defaults();

    // Throws std::runtime_error on an unknown key, or Slic3r::BadOptionValueException
    // on a value that does not parse for that key.
    // `used_filaments` is the number of filament presets the job uses.
    static Slic3r::DynamicPrintConfig build(Slic3r::DynamicPrintConfig base,
                                            const std::map<std::string, std::string>& overrides,
                                            int used_filaments = 1);
};

} // namespace orca::engine
