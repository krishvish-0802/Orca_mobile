#include "orca_engine/EngineInfo.hpp"

#include "libslic3r_version.h"

#ifndef ORCA_UPSTREAM_COMMIT
#define ORCA_UPSTREAM_COMMIT "unknown"
#endif

namespace orca::engine {

EngineInfo engine_info()
{
    return EngineInfo{SLIC3R_APP_NAME, SoftFever_VERSION, SLIC3R_VERSION, ORCA_UPSTREAM_COMMIT};
}

} // namespace orca::engine
