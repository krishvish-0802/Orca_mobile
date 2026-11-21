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

    std::string profiles_dir;
    std::mutex mutex;
    std::map<std::string, std::unique_ptr<Slic3r::PresetBundle>> bundles;
};

} // namespace orca::engine
