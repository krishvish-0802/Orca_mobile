#pragma once

// How settings depend on each other, as the desktop's settings tabs apply it
// (ConfigManipulation and the printer and filament tabs): which settings are
// greyed out or hidden and why, values the desktop sets on its own when others
// change, and conflicts it asks the user to resolve.

#include <map>
#include <optional>
#include <string>
#include <vector>

#include "orca_engine/PresetCatalog.hpp"

namespace orca::engine {

// Where the settings are edited: the presets (global), an object, part or
// height range (whose own settings go over the presets), or a plate.
enum class SettingsScope : int { Global = 0, Object = 1, Plate = 2 };

struct OptionState {
    bool visible = true;
    bool enabled = true;
    // Why it is hidden or greyed out (the desktop's tooltip); empty otherwise.
    std::string reason;
};

// One way out of a conflict: the settings to change (serialized as in profiles).
struct SettingsFix {
    std::string label;
    std::map<std::string, std::string> changes;
};

// A conflict the desktop asks about (spiral vase with walls, fuzzy skin modes
// without Arachne...). The first fix is the one the desktop recommends.
struct SettingsCheck {
    std::string id;
    std::string message;
    std::vector<SettingsFix> fixes;
};

struct SettingsReview {
    // Every setting a rule decides about; one missing is shown and editable.
    std::map<std::string, OptionState> options;
    // Settings shown under another name for these values (brim width as ear radius).
    std::map<std::string, std::string> labels;
    // Values the desktop sets on its own (with a notice, or silently); the
    // settings should take them.
    std::map<std::string, std::string> changes;
    // What those changes were for, when the desktop says so.
    std::vector<std::string> notices;
    std::vector<SettingsCheck> checks;
};

// The rules for `presets` (or the engine defaults) with `overrides` over them,
// edited at `scope`; `filament` is the filament slot whose settings are shown.
// Throws std::runtime_error when the settings do not resolve.
SettingsReview review_settings(const std::optional<PresetSelection>& presets,
                               const std::map<std::string, std::string>& overrides, SettingsScope scope, int filament,
                               PresetCatalog* catalog);

} // namespace orca::engine
