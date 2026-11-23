#pragma once

#include <cstddef>
#include <map>
#include <optional>
#include <string>

#include "orca_engine/PresetCatalog.hpp"
#include "orca_engine/Project.hpp"

namespace orca::engine {

// The config the plate tools use: the presets and overrides the slice will use,
// so the bed, brims, supports and fan direction are the ones that will print.
struct PlateConfig {
    std::optional<PresetSelection> presets;
    std::map<std::string, std::string> overrides;
};

// The desktop's arrange settings (GLCanvas3D::ArrangeSettings).
struct ArrangeOptions {
    double spacing_mm = 0;           // 0: automatic, from each object's brim and supports
    bool allow_rotation = false;     // may turn objects about Z to pack tighter
    bool align_to_y_axis = false;    // lay long objects along Y
};

struct PlateChange {
    std::size_t placed = 0;          // instances arranged on the bed, or copies added to it
    std::size_t not_placed = 0;      // instances or asked-for copies that did not fit (beside the bed);
                                     // always 0 when filling
};

// Packs every instance on the current plate, as the desktop's Arrange (ArrangeJob) does.
// What does not fit goes beside the bed, one bed's width further for each bed
// the packer would have needed; nothing moves when nothing fits.
PlateChange arrange(Project& project, const PlateConfig& config, const ArrangeOptions& options,
                    PresetCatalog* catalog);

// Arrange on every plate (the desktop's Arrange all plates): `all_plates` packs
// every instance, plate after plate, adding plates for what does not fit.
// Without it, as arrange().
PlateChange arrange_plates(Project& project, const PlateConfig& config, const ArrangeOptions& options,
                           PresetCatalog* catalog, bool all_plates);

// Turns instances so they print with the least support, as the desktop's
// Auto orient (OrientJob, "minimize support area") does: `only` one instance,
// or all when empty. Each lands back on the bed. Throws std::out_of_range for a bad reference.
void orient(Project& project, const std::optional<InstanceRef>& only, const PlateConfig& config,
            PresetCatalog* catalog);

// Adds copies of `source` in the free space of the bed, leaving everything else
// where it is. `count` copies (those that do not fit go beside the bed), or as
// many as fit when empty, like the desktop's Fill bed (FillBedJob), up to
// kMaxFillCopies. Throws std::out_of_range for a bad reference.
PlateChange add_copies(Project& project, const InstanceRef& source, std::optional<std::size_t> count,
                       const PlateConfig& config, const ArrangeOptions& options, PresetCatalog* catalog);

constexpr std::size_t kMaxFillCopies = 200;

} // namespace orca::engine
