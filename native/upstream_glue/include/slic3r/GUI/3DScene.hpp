// Stand-in for upstream's src/slic3r/GUI/3DScene.hpp, which pulls in the GUI's
// OpenGL helpers. The only upstream source that includes it here,
// slic3r/GUI/LibVGCode/LibVGCodeWrapper.cpp, needs nothing from it beyond what
// libslic3r already declares.
#pragma once

#include "libslic3r/Color.hpp"
#include "libslic3r/ExtrusionEntity.hpp"
#include "libslic3r/ExtrusionEntityCollection.hpp"
