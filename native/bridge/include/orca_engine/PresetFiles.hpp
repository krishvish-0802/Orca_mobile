#pragma once

#include <string>
#include <vector>

#include "orca_engine/PresetCatalog.hpp"

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

} // namespace orca::engine
