// adjust_color_for_rendering() as upstream defines it in src/slic3r/GUI/3DScene.cpp
// (OrcaSlicer 0c62aa0), which the desktop build compiles into its GUI. Keeps the
// preview's filament colors identical to the desktop app's.
#include <algorithm>

#include "slic3r/GUI/3DScene.hpp"

namespace {
constexpr float kFullyTransparentMaterialThreshold = 0.1f;   // FullyTransparentMaterialThreshold
constexpr float kFullBlackThreshold = 0.2f;                  // FULL_BLACK_THRESHOLD
} // namespace

Slic3r::ColorRGBA adjust_color_for_rendering(const Slic3r::ColorRGBA& colors)
{
    // a clear color keeps its hue, at the least opacity that still shows it
    const float alpha = std::max(colors.a(), kFullyTransparentMaterialThreshold);
    if (colors.r() < kFullBlackThreshold && colors.g() < kFullBlackThreshold && colors.b() < kFullBlackThreshold) {
        // black, lifted so that the shading shows, the more the more translucent
        const float grey = kFullBlackThreshold + 0.3f * (1.0f - alpha);
        return {grey, grey, grey, alpha};
    }
    return {colors.r(), colors.g(), colors.b(), alpha};
}
