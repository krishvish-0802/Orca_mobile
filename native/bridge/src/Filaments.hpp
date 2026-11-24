#pragma once

#include <string>
#include <vector>

#include "libslic3r/PrintConfig.hpp"

namespace orca::engine {

// What the desktop sets up for several filaments on one printer, besides the
// presets themselves (Plater's filament combo boxes and WipeTowerDialog):
// each filament's colour, every filament mapped to the first nozzle unless the
// profile maps them, and the purge volumes between each pair of colours.
//
// `colours` holds one "#RRGGBB" per filament; an empty one keeps the preset's.
void apply_filaments(Slic3r::DynamicPrintConfig& config, const std::vector<std::string>& colours);

// The purge volume matrix for the config's filament colours, one n x n block per
// nozzle, as Plater::on_filament_color_changed computes it.
std::vector<double> flush_volumes(const Slic3r::DynamicPrintConfig& config);

} // namespace orca::engine
