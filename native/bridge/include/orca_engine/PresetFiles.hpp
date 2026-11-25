#pragma once

#include <map>
#include <optional>
#include <string>
#include <vector>

#include "orca_engine/PresetCatalog.hpp"

namespace Slic3r { class DynamicPrintConfig; }

namespace orca::engine {

// The user presets a file the desktop wrote holds, and why any were left out.
struct PresetFileContents {
    std::vector<UserPreset> presets;
    std::vector<std::string> problems;
};

// Reads a preset file the desktop wrote: one preset (.json, as kept in
// user/<id>/<kind>/ or exported in a bundle) or a project's settings (a .3mf's
// Metadata/project_settings.config: its process, filaments and printer). Each
// preset found becomes a user preset over the `vendor` system preset it
// inherits, holding only what it sets differently. A preset whose parent isn't
// one of the vendor's (or the filament library's) is left out with a problem.
// The desktop's loader reads the file, so renamed and retired settings come in
// as they would there. Throws std::runtime_error for a file that is not a preset.
PresetFileContents read_preset_file(PresetCatalog& catalog, const std::string& vendor, const std::string& path);

// `preset` as the desktop saves a user preset (a .json its Import configs takes):
// "name", "from": "User", "version", "inherits", its settings id and its values.
std::string user_preset_json(const UserPreset& preset);

// The full settings a project file carries (its project_settings.config): the
// config a slice with `presets` and `overrides` uses, with what the desktop
// reads back into presets: each preset's name, the system preset each of the
// user's is based on (inherits_group) and the settings each one changes from
// its system preset (different_settings_to_system), in the desktop's order:
// process, filaments, printer.
Slic3r::DynamicPrintConfig project_settings(PresetCatalog* catalog, const std::optional<PresetSelection>& presets,
                                            const std::map<std::string, std::string>& overrides);

// The presets a project file was saved with, as they can be chosen here: its
// selection over `vendor`'s presets (each the preset it names, when there is one
// of that name, else the system preset it is based on), and the settings it
// changes over them (serialized). Problems say what couldn't be matched; the
// selection's printer is empty when the project's printer isn't one of the vendor's.
struct ProjectPresets {
    PresetSelection selection;
    std::map<std::string, std::string> changes;
    std::vector<std::string> problems;
};
ProjectPresets project_presets(PresetCatalog& catalog, const std::string& vendor, const Slic3r::DynamicPrintConfig& settings);

} // namespace orca::engine
