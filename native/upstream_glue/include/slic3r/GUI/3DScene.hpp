// Stand-in for upstream's src/slic3r/GUI/3DScene.hpp, which pulls in the GUI's
// OpenGL helpers. The only upstream source that includes it here,
// slic3r/GUI/LibVGCode/LibVGCodeWrapper.cpp, needs the libslic3r types below
// and adjust_color_for_rendering(), defined in upstream_glue/AdjustColor.cpp.
#pragma once

#include "libslic3r/Color.hpp"
#include "libslic3r/ExtrusionEntity.hpp"
#include "libslic3r/ExtrusionEntityCollection.hpp"

extern Slic3r::ColorRGBA adjust_color_for_rendering(const Slic3r::ColorRGBA& colors);
