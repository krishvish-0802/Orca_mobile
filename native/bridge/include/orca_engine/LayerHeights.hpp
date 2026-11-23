#pragma once

// The desktop's Variable layer height (GLCanvas3D::LayersEditing) for one
// object: its layer height profile, made adaptive, smoothed, or brushed
// thicker or thinner around a height.

#include <cstddef>
#include <vector>

#include "orca_engine/PlateTools.hpp"

namespace orca::engine {

struct LayerProfile {
    // The object's profile as the desktop keeps it: (z, layer height) pairs from
    // the bottom up, in mm above the object's base. Its own when `custom`, else
    // what its height ranges and the slice's layer height make.
    std::vector<double> profile;
    // The layers it makes: bottom and top of each, mm.
    std::vector<double> layers;
    double layer_height = 0;
    double min_layer_height = 0;
    double max_layer_height = 0;
    double object_height = 0;
    bool custom = false;
};

// Values are libslic3r's LayerHeightEditActionType.
enum class LayerBrush : int { Increase = 0, Decrease = 1, Reduce = 2, Smooth = 3 };

// The profile the slice will use. Throws std::out_of_range for a bad index.
LayerProfile layer_profile(const Project& project, std::size_t object, const PlateConfig& config, PresetCatalog* catalog);

// Each of these changes the object's profile and returns it.
// Adaptive: thinner where the surface slopes, `quality` 0 (fast) .. 1 (fine).
LayerProfile adaptive_layer_profile(Project& project, std::size_t object, const PlateConfig& config, PresetCatalog* catalog,
                                    double quality);
LayerProfile smooth_layer_profile(Project& project, std::size_t object, const PlateConfig& config, PresetCatalog* catalog,
                                  unsigned radius, bool keep_min);
// One brush step at height `z` (mm above the object's base): `delta` mm over a
// band `band_width` mm high, as a drag on the desktop's layer height bar.
LayerProfile brush_layer_profile(Project& project, std::size_t object, const PlateConfig& config, PresetCatalog* catalog,
                                 double z, double delta, double band_width, LayerBrush brush);
// Back to the slice's layer height (and the height ranges).
LayerProfile reset_layer_profile(Project& project, std::size_t object, const PlateConfig& config, PresetCatalog* catalog);

} // namespace orca::engine
