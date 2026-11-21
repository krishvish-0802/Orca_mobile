#pragma once

#include <cstddef>
#include <map>
#include <mutex>
#include <optional>
#include <string>

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

struct SliceOutcome {
    enum class Status { Succeeded, Canceled, Failed };
    Status status = Status::Failed;
    std::string error;               // set when Failed
    std::optional<SliceStatistics> statistics;
};

// Loads a model, slices it with the given config and writes G-code.
// `catalog` resolves request.presets and may be null when none are given.
// Blocking; run it off the UI thread.
SliceOutcome run_slice(const SliceRequest& request, ProgressSink& progress, CancelToken& cancel,
                       PresetCatalog* catalog = nullptr);

} // namespace orca::engine
