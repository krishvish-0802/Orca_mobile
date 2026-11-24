#pragma once

#include <map>
#include <optional>
#include <string>

#include "libslic3r/PrintConfig.hpp"

namespace orca::engine {

class PresetCatalog;
struct PresetSelection;

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
    // Unless `normalize` is false, the values a slice forces follow (spiral vase
    // mode's single wall, for one); the settings review wants them as given.
    static Slic3r::DynamicPrintConfig build(Slic3r::DynamicPrintConfig base,
                                            const std::map<std::string, std::string>& overrides,
                                            int used_filaments = 1, bool normalize = true);

    // The config a slice with `presets` (composed from `catalog`) or, without
    // them, the defaults, and `overrides` uses. Plate tools use it too, so they
    // see the same bed, brims and supports as the slice.
    // Throws std::runtime_error when presets are given without a catalog.
    static Slic3r::DynamicPrintConfig resolve(const std::optional<PresetSelection>& presets,
                                              const std::map<std::string, std::string>& overrides,
                                              PresetCatalog* catalog, bool normalize = true);
};

} // namespace orca::engine
