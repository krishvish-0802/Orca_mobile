#include "orca_engine/SliceJob.hpp"

#include <exception>
#include <filesystem>

#include "ConfigBuilder.hpp"
#include "PresetCatalogImpl.hpp"
#include "orca_engine/Project.hpp"
#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintBase.hpp"

namespace orca::engine {

// --- CancelToken ---------------------------------------------------------

void CancelToken::request_cancel()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_requested = true;
    if (m_print != nullptr)
        m_print->cancel();
}

bool CancelToken::is_cancel_requested() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_requested;
}

void CancelToken::attach(Slic3r::PrintBase* print)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_print = print;
    if (m_requested)
        m_print->cancel();
}

void CancelToken::detach()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_print = nullptr;
}

// Grants run_slice() access to CancelToken's attach/detach without making
// them part of the public API.
class SliceJobAccess {
public:
    // Attaches for the lifetime of the scope, so a cancel can never reach a
    // Print that has been destroyed.
    class ScopedAttach {
    public:
        ScopedAttach(CancelToken& token, Slic3r::PrintBase& print) : m_token(token) { m_token.attach(&print); }
        ~ScopedAttach() { m_token.detach(); }
        ScopedAttach(const ScopedAttach&) = delete;
        ScopedAttach& operator=(const ScopedAttach&) = delete;

    private:
        CancelToken& m_token;
    };
};

// --- run_slice -----------------------------------------------------------

namespace {

// Center of the bed's printable area, where the job's objects are placed.
Slic3r::Vec2d bed_center(const Slic3r::DynamicPrintConfig& config)
{
    const auto* area = config.opt<Slic3r::ConfigOptionPoints>("printable_area");
    if (area == nullptr || area->values.empty())
        return Slic3r::Vec2d::Zero();
    Slic3r::BoundingBoxf box;
    for (const Slic3r::Vec2d& point : area->values)
        box.merge(point);
    return box.center();
}

Slic3r::Model load_model(const std::string& path)
{
    using Slic3r::LoadStrategy;
    Slic3r::Model model = Slic3r::Model::read_from_file(
        path, nullptr, nullptr, LoadStrategy::AddDefaultInstances | LoadStrategy::LoadModel);
    if (model.objects.empty())
        throw std::runtime_error("The file contains no printable objects: " + path);
    for (Slic3r::ModelObject* object : model.objects)
        object->ensure_on_bed();
    return model;
}

SliceStatistics collect_statistics(const Slic3r::Print& print, const Slic3r::GCodeProcessorResult& result,
                                   const std::string& gcode_path)
{
    using Mode = Slic3r::PrintEstimatedStatistics::ETimeMode;
    SliceStatistics stats;
    stats.print_time_seconds = result.print_statistics.modes[static_cast<size_t>(Mode::Normal)].time;
    stats.filament_used_mm = print.print_statistics().total_used_filament;
    stats.filament_used_g = print.print_statistics().total_weight;
    for (const Slic3r::PrintObject* object : print.objects())
        stats.layer_count = std::max(stats.layer_count, object->layer_count());
    std::error_code ec;
    const auto size = std::filesystem::file_size(gcode_path, ec);
    stats.gcode_bytes = ec ? 0 : static_cast<std::size_t>(size);
    return stats;
}

} // namespace

namespace {

// The common pipeline: config, apply, validate, process, export.
SliceOutcome slice_model(Slic3r::Model& model, bool center_on_bed, const SliceRequest& request,
                         ProgressSink& progress, CancelToken& cancel, PresetCatalog* catalog)
{
    SliceOutcome outcome;
    try {
        Slic3r::DynamicPrintConfig config =
            ConfigBuilder::resolve(request.presets, request.config_overrides, catalog);
        if (center_on_bed)
            model.center_instances_around_point(bed_center(config));

        Slic3r::Print print;
        print.set_status_callback([&progress](const Slic3r::PrintBase::SlicingStatus& status) {
            if (status.percent >= 0)
                progress.on_progress(status.percent, status.text);
        });
        SliceJobAccess::ScopedAttach attached(cancel, print);

        print.apply(model, config);
        const Slic3r::StringObjectException error = print.validate();
        if (!error.string.empty()) {
            outcome.error = error.string;
            return outcome;
        }
        if (print.empty()) {
            outcome.error = "Nothing to slice: no object lies fully inside the print volume.";
            return outcome;
        }

        print.process();
        Slic3r::GCodeProcessorResult result;
        // Upstream creates the output's parent folder and expects it to exist or be
        // named; a bare file name has an empty parent, so resolve it first.
        const std::string gcode_path = std::filesystem::absolute(request.output_gcode_path).string();
        print.export_gcode(gcode_path, &result, nullptr);

        outcome.status = SliceOutcome::Status::Succeeded;
        outcome.statistics = collect_statistics(print, result, gcode_path);
    } catch (const Slic3r::CanceledException&) {
        outcome.status = SliceOutcome::Status::Canceled;
    } catch (const std::exception& e) {
        outcome.status = SliceOutcome::Status::Failed;
        outcome.error = e.what();
    }
    return outcome;
}

} // namespace

SliceOutcome run_slice(const SliceRequest& request, ProgressSink& progress, CancelToken& cancel, PresetCatalog* catalog)
{
    try {
        Slic3r::Model model = load_model(request.model_path);
        return slice_model(model, true, request, progress, cancel, catalog);
    } catch (const std::exception& e) {
        SliceOutcome outcome;
        outcome.error = e.what();
        return outcome;
    }
}

SliceOutcome run_slice(const Project& project, const SliceRequest& request, ProgressSink& progress,
                       CancelToken& cancel, PresetCatalog* catalog)
{
    // Slice a copy of the current plate: the project stays editable while (and
    // after) it slices. The plate's own settings come over the request's.
    Slic3r::Model model = project.plate_model_copy();
    if (model.objects.empty()) {
        SliceOutcome outcome;
        outcome.error = "This plate is empty: put an object on it first.";
        return outcome;
    }
    SliceRequest plate_request = request;
    for (const auto& [key, value] : project.plate_settings())
        plate_request.config_overrides[key] = value;
    return slice_model(model, false, plate_request, progress, cancel, catalog);
}

} // namespace orca::engine
