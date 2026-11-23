// RGB2HSV() as upstream defines it in src/slic3r/Utils/ColorSpaceConvert.cpp
// (OrcaSlicer 0c62aa0), which the desktop build compiles with its GUI (the file
// includes wx). libslic3r's FlushVolCalculator needs it to compute purge
// volumes from filament colours.
#include <algorithm>
#include <cmath>

// The input r, g, b values should be in range [0, 1]. The output h is in range
// [0, 360], s is in range [0, 1] and v is in range [0, 1].
void RGB2HSV(float r, float g, float b, float* h, float* s, float* v)
{
    const float Cmax = std::max(std::max(r, g), b);
    const float Cmin = std::min(std::min(r, g), b);
    const float delta = Cmax - Cmin;

    if (std::abs(delta) < 0.001)
        *h = 0.f;
    else if (Cmax == r)
        *h = 60.f * std::fmod((g - b) / delta, 6.f);
    else if (Cmax == g)
        *h = 60.f * ((b - r) / delta + 2);
    else
        *h = 60.f * ((r - g) / delta + 4);

    if (std::abs(Cmax) < 0.001)
        *s = 0.f;
    else
        *s = delta / Cmax;

    *v = Cmax;
}
