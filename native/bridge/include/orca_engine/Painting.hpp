#pragma once

#include <array>
#include <cstddef>
#include <memory>
#include <optional>
#include <vector>

#include "orca_engine/Project.hpp"

namespace orca::engine {

// What is painted, as the desktop's painting gizmos: support enforcers and
// blockers, seam enforcers and blockers, fuzzy skin, filament colours, and the
// area of a painted modifier (a modifier shaped by paint on a part).
enum class PaintKind : int { Supports = 0, Seam = 1, FuzzySkin = 2, Color = 3, PaintedModifier = 4 };

// How a touch paints: a circle brush on the surface, a sphere brush through it,
// a smart fill (spreads over faces within an angle), or a fill of the whole
// connected surface.
enum class PaintTool : int { Circle = 0, Sphere = 1, SmartFill = 2, BucketFill = 3 };

// One brush step. `hit` and `camera` are in the painted volume's own
// coordinates; `previous` (same volume) joins this step to the last one so a
// quick stroke leaves no gaps. `state` is what the brush paints: 0 erases;
// for supports and seams 1 enforces and 2 blocks; for fuzzy skin 1; for colour
// the filament number (1..).
struct PaintStroke {
    InstanceRef via;                   // the instance the volume is seen through
    std::size_t volume = 0;
    int facet = 0;                     // triangle hit, index in the volume's mesh
    std::array<float, 3> hit{};
    std::array<float, 3> camera{};
    std::optional<std::array<float, 3>> previous;
    int previous_facet = 0;
    int state = 1;
    PaintTool tool = PaintTool::Circle;
    float radius_mm = 2.f;             // brush radius on the plate
    float fill_angle_deg = 30.f;       // smart fill: the largest angle it spreads over
    float overhang_angle_deg = 0.f;    // paint only overhangs steeper than this (0: everywhere)
};

// Painted triangles of one volume for drawing, in its own coordinates.
struct PaintOverlay {
    struct Run {
        int state = 0;
        std::size_t first_triangle = 0;
        std::size_t triangle_count = 0;
    };
    std::vector<float> positions;      // x, y, z per vertex, three per triangle
    std::vector<Run> runs;             // one per painted state
};

// Painting one object's parts, as a desktop painting gizmo between opening and
// closing: the paint is kept split as fine as the brush needs and written to the
// model after every stroke (commit), so a slice uses it. The object's volumes
// must not change while it is open.
class PaintSession {
public:
    // Throws std::out_of_range for a bad object. For PaintedModifier, `target` is
    // the painted modifier the strokes shape (painted on its part only); without
    // one the first stroke makes a new one on the part it lands on.
    PaintSession(Project& project, std::size_t object, PaintKind kind, std::optional<std::size_t> target = std::nullopt);
    ~PaintSession();
    PaintSession(const PaintSession&) = delete;
    PaintSession& operator=(const PaintSession&) = delete;

    std::size_t object() const;
    PaintKind kind() const;
    // Volumes that can be painted (the object's parts), by index in the object.
    std::vector<std::size_t> paintable_volumes() const;

    // Returns false when the volume cannot be painted.
    bool paint(const PaintStroke& stroke);
    // Erases everything this session's kind painted on the object.
    void clear();
    // Paints every face of every part with `state` (as a fill of everything).
    void paint_all(int state);
    // Writes the paint into the model; true when it changed.
    bool commit();
    // The painted modifier the strokes shape, once there is one.
    std::optional<std::size_t> target() const;

    PaintOverlay overlay(std::size_t volume) const;

private:
    struct Impl;
    std::unique_ptr<Impl> m_impl;
};

} // namespace orca::engine
