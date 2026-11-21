// libslic3r calls NanoSVG (NSVGUtils, Format/svg, CAD/SketchImport) but does not
// compile its implementation: on desktop, src/slic3r/GUI/BitmapCache.cpp does.
// The GUI is not built for Android, so the implementation is provided here.
#define NANOSVG_IMPLEMENTATION
#include "nanosvg/nanosvg.h"
