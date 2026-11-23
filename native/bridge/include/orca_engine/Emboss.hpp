#pragma once

// The desktop's Emboss (GLGizmoEmboss) and SVG (GLGizmoSVG) tools: text in a
// font, or the shapes of an SVG file, raised from a surface as a part,
// engraved into it as a negative volume, or added as an object of its own.

#include <array>
#include <cstddef>
#include <optional>
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
// part, sunk into the surface for a negative volume or modifier. With
// `use_surface` it follows the surface's shape (the desktop's "Use surface").
// Returns the new volume's index. The object stays on the bed. The volume keeps
// what it was made from, as the desktop's text_configuration and emboss_shape,
// so it can be edited again (and is saved to 3MF as the desktop saves it).
std::size_t emboss_on_surface(Project& project, const FeaturePick& at, VolumeKind kind, const EmbossSource& source,
                              bool use_surface = false);

// An embossed volume's settings: what it was made from, its kind and whether it
// follows the surface.
struct EmbossedVolume {
    EmbossSource source;
    VolumeKind kind = VolumeKind::ModelPart;
    bool use_surface = false;
    std::array<double, 2> size{};      // its shape's width and height, mm
};

// The settings of volume `volume` of `object` when it is a text or an SVG; nothing otherwise.
std::optional<EmbossedVolume> embossed(const Project& project, std::size_t object, std::size_t volume);

// Makes the embossed volume anew from `source`, as `kind`, where it stands.
// Throws std::invalid_argument for a volume that is not embossed, or what
// emboss_on_surface() throws.
void emboss_update(Project& project, std::size_t object, std::size_t volume, VolumeKind kind, const EmbossSource& source,
                   bool use_surface);

// Moves the embossed volume onto another part's surface at `at` (on the same
// object), keeping its turn about the surface and its size. A text that follows
// the surface keeps its old shape until emboss_update() (as the desktop updates
// it when the drag ends).
void emboss_move(Project& project, std::size_t volume, const FeaturePick& at);

// Turns the embossed volume about its own normal.
void emboss_rotate(Project& project, std::size_t object, std::size_t volume, double radians);

// Puts the object back on the bed after a text moved (a raised text may reach below it).
void emboss_settle(Project& project, std::size_t object);

// As a new object lying on the bed, centered at (x, y). Returns its index.
std::size_t emboss_object(Project& project, const EmbossSource& source, double x, double y);

} // namespace orca::engine
