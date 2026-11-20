#pragma once

#include <string>

namespace orca::engine {

// Directories libslic3r reads from or writes to. On Android these are
// app-private paths supplied by the Kotlin layer.
struct EnvironmentPaths {
    std::string resources_dir;  // read-only data unpacked from the APK (profiles, calib, ...)
    std::string data_dir;       // persistent app data (user presets)
    std::string temp_dir;       // scratch space for intermediate files
};

// Logging verbosity, matching libslic3r's set_logging_level() scale.
enum class LogLevel : unsigned { Fatal = 0, Error = 1, Warning = 2, Info = 3, Debug = 4, Trace = 5 };

// Points libslic3r at its directories. Call once before any slicing.
void initialize_environment(const EnvironmentPaths& paths, LogLevel level = LogLevel::Warning);

} // namespace orca::engine
