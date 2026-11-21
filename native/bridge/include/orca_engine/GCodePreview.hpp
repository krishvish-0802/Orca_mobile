#pragma once

#include <cstddef>
#include <memory>
#include <string>
#include <vector>

namespace libvgcode { struct GCodeInputData; }

namespace orca::engine {

// What the preview screen lists about a G-code file.
struct PreviewSummary {
    std::vector<float> layer_zs;               // top of each layer, mm
    double print_time_seconds = 0;             // normal-mode estimate
    std::size_t move_count = 0;
    std::vector<std::string> filament_colors;  // "#RRGGBB" per extruder
};

// A G-code file read by OrcaSlicer's GCodeProcessor and converted into the
// toolpaths libvgcode draws, the same pipeline as the desktop preview. It reads
// any G-code, so files sliced elsewhere open too.
class GCodePreview {
public:
    // Throws std::runtime_error if the file cannot be read or holds no moves.
    static std::unique_ptr<GCodePreview> load(const std::string& gcode_path);
    ~GCodePreview();
    GCodePreview(const GCodePreview&) = delete;
    GCodePreview& operator=(const GCodePreview&) = delete;

    const PreviewSummary& summary() const;
    // Shared with the viewport, which uploads it on the GL thread.
    std::shared_ptr<const libvgcode::GCodeInputData> toolpaths() const;

private:
    GCodePreview();
    struct Impl;
    std::unique_ptr<Impl> m_impl;
};

} // namespace orca::engine
