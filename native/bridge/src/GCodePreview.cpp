#include "orca_engine/GCodePreview.hpp"

#include <algorithm>
#include <stdexcept>

#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libvgcode/include/GCodeInputData.hpp"
#include "libvgcode/include/Viewer.hpp"
#include "slic3r/GUI/LibVGCode/LibVGCodeWrapper.hpp"

namespace orca::engine {

struct GCodePreview::Impl {
    PreviewSummary summary;
    std::shared_ptr<const libvgcode::GCodeInputData> toolpaths;
};

GCodePreview::GCodePreview() : m_impl(std::make_unique<Impl>()) {}
GCodePreview::~GCodePreview() = default;

namespace {

std::vector<std::string> filament_colors(const Slic3r::DynamicConfig& render_config)
{
    if (const auto* colors = render_config.option<Slic3r::ConfigOptionStrings>("filament_colour"))
        return colors->values;
    return {};
}

std::vector<float> layer_tops(const libvgcode::GCodeInputData& data)
{
    std::vector<float> zs;
    for (const libvgcode::PathVertex& v : data.vertices) {
        if (!v.is_extrusion())
            continue;
        if (v.layer_id >= zs.size())
            zs.resize(v.layer_id + 1, 0.0f);
        zs[v.layer_id] = std::max(zs[v.layer_id], v.position[2]);
    }
    return zs;
}

} // namespace

std::unique_ptr<GCodePreview> GCodePreview::load(const std::string& gcode_path)
{
    // As the desktop app does for a G-code file opened on its own (Plater::load_gcode).
    Slic3r::GCodeProcessor processor;
    processor.init_filament_maps_and_nozzle_type_when_import_only_gcode();
    Slic3r::GCodeProcessor::s_IsBBLPrinter = false;
    processor.process_file(gcode_path);
    // The result holds a mutex: assigned, never constructed from another.
    Slic3r::GCodeProcessorResult result;
    result = processor.extract_result();
    if (result.moves.empty())
        throw std::runtime_error("The file holds no G-code moves: " + gcode_path);

    std::unique_ptr<GCodePreview> preview(new GCodePreview());
    PreviewSummary& summary = preview->m_impl->summary;
    summary.filament_colors = filament_colors(processor.export_config_for_render());
    summary.print_time_seconds = result.print_statistics
        .modes[static_cast<std::size_t>(Slic3r::PrintEstimatedStatistics::ETimeMode::Normal)].time;
    summary.move_count = result.moves.size();

    // The converter only reads view options from the viewer; it needs no GL context.
    const libvgcode::Viewer options;
    auto data = std::make_shared<libvgcode::GCodeInputData>(
        libvgcode::convert(result, summary.filament_colors, {}, options));
    summary.layer_zs = layer_tops(*data);
    preview->m_impl->toolpaths = std::move(data);
    return preview;
}

const PreviewSummary& GCodePreview::summary() const { return m_impl->summary; }

std::shared_ptr<const libvgcode::GCodeInputData> GCodePreview::toolpaths() const { return m_impl->toolpaths; }

} // namespace orca::engine
