#pragma once

#include <map>
#include <string>

#include "libslic3r/PrintConfig.hpp"

namespace orca::engine {

// Builds the full print config for a slice: OrcaSlicer's defaults with the
// caller's overrides applied, normalized the way the desktop CLI does for a
// single-extruder, single-plate job.
class ConfigBuilder {
public:
    // Throws Slic3r::BadOptionTypeException / std::runtime_error on an unknown
    // key or a value that does not parse for that key.
    static Slic3r::DynamicPrintConfig build(const std::map<std::string, std::string>& overrides);
};

} // namespace orca::engine
