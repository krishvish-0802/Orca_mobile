#pragma once

#include <map>
#include <optional>
#include <string>
#include <vector>

namespace orca::engine {

// One configuration option as OrcaSlicer defines it (PrintConfigDef), for a
// settings screen to show and validate it the way the desktop app does.
struct OptionInfo {
    std::string key;
    // "float", "floats", "int", "ints", "string", "strings", "percent", "percents",
    // "floatOrPercent", "floatsOrPercents", "bool", "bools", "enum", "enums", "point", "points"
    std::string type;
    std::string label;          // short, as in a settings group
    std::string full_label;     // stand-alone, e.g. in search results
    std::string category;       // e.g. "Quality", "Strength", "Support"
    std::string tooltip;
    std::string unit;           // sidetext, e.g. "mm", "°C", "%"
    std::string mode;           // "simple", "advanced", "expert" or "develop"
    std::string default_value;  // serialized as in profiles
    std::optional<double> min;  // absent when unbounded
    std::optional<double> max;
    std::vector<std::string> enum_values;
    std::vector<std::string> enum_labels;
    bool nullable = false;
    bool multiline = false;     // edited as several lines (custom G-code, notes)
};

// Definitions of `keys`, in that order; unknown keys are left out.
std::vector<OptionInfo> describe_options(const std::vector<std::string>& keys);

// Which options each kind of preset holds, as OrcaSlicer's Preset::print_options(),
// filament_options() and printer_options() (with machine_limits_options()) list them.
struct PresetOptionKeys {
    std::vector<std::string> process;
    std::vector<std::string> filament;
    std::vector<std::string> printer;
};
PresetOptionKeys preset_option_keys();

// Values of `keys` in the engine's built-in defaults (the config a slice without
// presets starts from), serialized as in profiles. Unknown keys are left out.
std::map<std::string, std::string> default_values(const std::vector<std::string>& keys);

} // namespace orca::engine
