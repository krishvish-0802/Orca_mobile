#pragma once

#include <optional>

#include "libslic3r/PrintConfig.hpp"
#include "orca_engine/PlateTools.hpp"
#include "orca_engine/Project.hpp"

namespace orca::engine::detail {

// Where the current plate's prime tower goes, as PartPlate::estimate_wipe_tower_polygon
// places it: its saved spot (wipe_tower_x/y), kept on the bed; nothing when the
// plate prints no tower. `config` is the slice's config, plate settings included.
std::optional<WipeTowerPlacement> place_wipe_tower(const Project& project, const Slic3r::DynamicPrintConfig& config);

} // namespace orca::engine::detail
