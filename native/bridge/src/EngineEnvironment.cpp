#include "orca_engine/EngineEnvironment.hpp"

#include "libslic3r/Utils.hpp"

namespace orca::engine {

void initialize_environment(const EnvironmentPaths& paths, LogLevel level)
{
    Slic3r::set_logging_level(static_cast<unsigned>(level));
    if (!paths.resources_dir.empty())
        Slic3r::set_resources_dir(paths.resources_dir);
    if (!paths.data_dir.empty())
        Slic3r::set_data_dir(paths.data_dir);
    if (!paths.temp_dir.empty())
        Slic3r::set_temporary_dir(paths.temp_dir);
}

} // namespace orca::engine
