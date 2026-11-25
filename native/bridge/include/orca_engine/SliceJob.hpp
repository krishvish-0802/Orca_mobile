#pragma once

#include <cstddef>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "orca_engine/PresetCatalog.hpp"

namespace Slic3r { class PrintBase; }

namespace orca::engine {

// Receives progress from a running slice. Called on the slicing thread.
class ProgressSink {
public:
    virtual ~ProgressSink() = default;
    virtual void on_progress(int percent, const std::string& message) = 0;
};

// Lets another thread cancel a slice. A cancel request made before the slice
// starts is honored as soon as it does.
class CancelToken {
public:
    void request_cancel();
    bool is_cancel_requested() const;

private:
    friend class SliceJobAccess;
    void attach(Slic3r::PrintBase* print);
    void detach();

    mutable std::mutex m_mutex;
    bool m_requested = false;
    Slic3r::PrintBase* m_print = nullptr;
};

struct SliceRequest {
    std::string model_path;          // STL, 3MF, OBJ, STEP, ...
    std::string output_gcode_path;
    // System presets to slice with. Without them the slice uses OrcaSlicer's
    // built-in defaults, which describe no real printer.
    std::optional<PresetSelection> presets;
    // Config keys and serialized values applied last, over the presets or the
    // defaults, e.g. {"layer_height", "0.2"}. Keys are validated against PrintConfigDef.
    std::map<std::string, std::string> config_overrides;
};

struct SliceStatistics {
    double print_time_seconds = 0;   // normal-mode estimate
    double filament_used_mm = 0;
    double filament_used_g = 0;
    std::size_t layer_count = 0;
    std::size_t gcode_bytes = 0;
};

// Something the slicer said about the print: why it failed, or a warning.
struct SliceMessage {
    enum class Level { Info, Warning, Error };
    Level level = Level::Warning;
    std::string text;
    std::string object;   // the object it is about, by name; empty for the whole plate
    std::string setting;  // the setting it is about (its key), when the slicer names one
    // What kind of message it is, for the app's fixes: "need_support",
    // "empty_first_layer", "floating", "too_tall", "no_layers", "too_small",
    // "support_enforcers", "spiral_vase", "prime_tower", "bed_temperature",
    // "timelapse"..., or empty.
    std::string code;
};

struct SliceOutcome {
    enum class Status { Succeeded, Canceled, Failed };
    Status status = Status::Failed;
    std::string error;               // set when Failed
    std::optional<SliceStatistics> statistics;
    // Every error and warning, the error first (Print::validate's, each step's,
    // the G-code processor's), each once.
    std::vector<SliceMessage> messages;
};

class Project;

// Loads a model, slices it with the given config and writes G-code.
// `catalog` resolves request.presets and may be null when none are given.
// Blocking; run it off the UI thread.
SliceOutcome run_slice(const SliceRequest& request, ProgressSink& progress, CancelToken& cancel,
                       PresetCatalog* catalog = nullptr);

// Slices a loaded project as placed (objects are not re-centered);
// request.model_path is ignored.
SliceOutcome run_slice(const Project& project, const SliceRequest& request, ProgressSink& progress,
                       CancelToken& cancel, PresetCatalog* catalog = nullptr);

} // namespace orca::engine
