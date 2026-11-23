#pragma once

// The desktop's Emboss (GLGizmoEmboss) and SVG (GLGizmoSVG) tools: text in a
// font, or the shapes of an SVG file, raised from a surface as a part,
// engraved into it as a negative volume, or added as an object of its own.

#include <cstddef>
#include <string>

#include "orca_engine/MeshTools.hpp"
#include "orca_engine/Project.hpp"

namespace orca::engine {

// What to emboss: text in the font at `font_path` (a .ttf, .otf or .ttc), or
// the SVG file at `svg_path`.
struct EmbossSource {
    std::string text;
    std::string font_path;
    double letter_height_mm = 10;      // text: the height of a line
    double char_gap = 0;               // text: extra space between letters, in font points
    double line_gap = 0;               // text: extra space between lines, in font points
    double boldness_mm = 0;            // text: thicker (or thinner when negative) strokes
    double skew = 0;                   // text: italic slant, x per y
    std::string svg_path;
    double svg_width_mm = 0;           // SVG: its width; 0 keeps its own size
    double depth_mm = 2;               // how far it stands out or cuts in
};

// The shape's size in mm (width, height), for a preview before placing it.
// Throws std::invalid_argument for a font or file that cannot be read, or text
// with nothing to emboss.
std::array<double, 2> emboss_size(const EmbossSource& source);

// On the surface tapped (`at`): text upright as seen from above, raised for a
// part, sunk into the surface for a negative volume or modifier. Returns the new
// volume's index. The object stays on the bed.
std::size_t emboss_on_surface(Project& project, const FeaturePick& at, VolumeKind kind, const EmbossSource& source);

// As a new object lying on the bed, centered at (x, y). Returns its index.
std::size_t emboss_object(Project& project, const EmbossSource& source, double x, double y);

} // namespace orca::engine
