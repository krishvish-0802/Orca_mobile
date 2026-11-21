#pragma once

#include <string>

namespace orca::engine {

// Identifies the engine build: OrcaSlicer's version and the pinned upstream commit.
struct EngineInfo {
    std::string app_name;          // "OrcaSlicer"
    std::string orca_version;      // e.g. "2.6.0-dev"
    std::string slic3r_version;    // e.g. "02.08.01.55"
    std::string upstream_commit;   // short hash of third_party/OrcaSlicer
};

EngineInfo engine_info();

} // namespace orca::engine
