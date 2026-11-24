#pragma once

#include <map>
#include <memory>
#include <mutex>
#include <string>

#include "libslic3r/PresetBundle.hpp"
#include "orca_engine/PresetCatalog.hpp"

namespace orca::engine {

struct PresetCatalog::Impl {
    explicit Impl(std::string dir) : profiles_dir(std::move(dir)) {}

    // Loads `vendor` (and the filament library) on first use. Caller holds `mutex`.
    Slic3r::PresetBundle& bundle_for(const std::string& vendor);

    // Full slicing config for a selection: OrcaSlicer's own composition of
    // printer + process + filaments (PresetBundle::construct_full_config).
    Slic3r::DynamicPrintConfig compose(const PresetSelection& selection);

    // `name` of `kind` from the bundle: a system preset, or a user preset
    // composed onto a copy of the one it inherits. Throws std::runtime_error
    // for an unknown name. Caller holds `mutex`.
    Slic3r::Preset resolve(Slic3r::PresetBundle& bundle, PresetKind kind, const std::string& name);

    // The user's presets of `kind` that inherit `system` (a system preset's name).
    std::vector<std::string> user_children(PresetKind kind, const std::string& system) const;

    std::string profiles_dir;
    std::mutex mutex;
    std::map<std::string, std::unique_ptr<Slic3r::PresetBundle>> bundles;
    std::map<std::pair<PresetKind, std::string>, UserPreset> user_presets;
};

} // namespace orca::engine
