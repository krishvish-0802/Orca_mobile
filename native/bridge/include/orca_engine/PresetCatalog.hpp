#pragma once

#include <array>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace orca::engine {

// The printer, process and filament presets a slice uses, by their OrcaSlicer
// system preset names (for example "Creality K1 Max (0.4 nozzle)").
struct PresetSelection {
    std::string vendor;                  // vendor bundle id, e.g. "Creality"
    std::string printer;
    std::string process;
    std::vector<std::string> filaments;  // one per extruder/filament slot
    // "#RRGGBB" per slot over the preset's own colour; empty (or shorter) keeps it.
    std::vector<std::string> filament_colours;
};

// The three kinds of preset.
enum class PresetKind : int { Printer = 0, Process = 1, Filament = 2 };

// A preset the user saved, as the desktop keeps one (user/<id>/<kind>/<name>.json):
// a system preset (`inherits`) with the user's values over it, serialized as in
// profiles. It shows wherever its system preset would, under its own name.
struct UserPreset {
    PresetKind kind = PresetKind::Process;
    std::string name;
    std::string inherits;
    std::map<std::string, std::string> values;
};

// Presets that suit one printer, with the defaults its profile recommends.
struct CompatiblePresets {
    std::vector<std::string> processes;
    std::vector<std::string> filaments;
    std::string default_process;         // empty when the profile names none that is compatible
    std::string default_filament;
};

// The printable area of a printer: its bed outline (mm, usually a rectangle)
// and maximum print height.
struct BedShape {
    std::vector<std::array<double, 2>> outline;
    double max_height = 0;
};

// OrcaSlicer's bundled system profiles (resources/profiles), loaded one vendor
// at a time together with the Orca filament library that vendors inherit from.
// Thread-safe; loaded vendors are kept for the catalog's lifetime.
class PresetCatalog {
public:
    // `profiles_dir` holds <Vendor>.json files and <Vendor>/ trees.
    explicit PresetCatalog(std::string profiles_dir);
    ~PresetCatalog();
    PresetCatalog(const PresetCatalog&) = delete;
    PresetCatalog& operator=(const PresetCatalog&) = delete;

    const std::string& profiles_dir() const;

    // Vendor ids, sorted, excluding the filament library.
    std::vector<std::string> vendors() const;

    // Visible system printer presets of `vendor`, sorted.
    std::vector<std::string> printers(const std::string& vendor);

    // Processes and filaments compatible with `printer`. Throws std::runtime_error
    // if the vendor or printer is unknown.
    CompatiblePresets compatible_presets(const std::string& vendor, const std::string& printer);

    // The printer's printable_area and printable_height. Throws std::runtime_error
    // if the vendor or printer is unknown.
    BedShape bed_shape(const std::string& vendor, const std::string& printer);

    // Values of `keys` in the config a slice with `selection` uses (printer, process
    // and filaments composed as the desktop app does), serialized as in profiles.
    // Unknown keys are left out. Throws std::runtime_error for unknown presets.
    std::map<std::string, std::string> config_values(const PresetSelection& selection,
                                                     const std::vector<std::string>& keys);

    // The user's presets, replacing those given before. A selection may name them
    // like system presets; printers() and compatible_presets() list them (after
    // the system ones) wherever the preset they inherit is listed.
    void set_user_presets(std::vector<UserPreset> presets);

    // Internal: implementation shared with the config builder.
    struct Impl;
    Impl& impl();

private:
    std::unique_ptr<Impl> m_impl;
};

} // namespace orca::engine
