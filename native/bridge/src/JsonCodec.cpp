#include "orca_engine/Json.hpp"

#include "nlohmann/json.hpp"

namespace orca::engine::json {

namespace {
const char* status_name(SliceOutcome::Status status)
{
    switch (status) {
    case SliceOutcome::Status::Succeeded: return "succeeded";
    case SliceOutcome::Status::Canceled: return "canceled";
    case SliceOutcome::Status::Failed: return "failed";
    }
    return "failed";
}
} // namespace

std::string to_json(const EngineInfo& info)
{
    nlohmann::json j{
        {"appName", info.app_name},
        {"orcaVersion", info.orca_version},
        {"slic3rVersion", info.slic3r_version},
        {"upstreamCommit", info.upstream_commit},
    };
    return j.dump();
}

std::string to_json(const SliceOutcome& outcome)
{
    nlohmann::json j{{"status", status_name(outcome.status)}};
    if (!outcome.error.empty())
        j["error"] = outcome.error;
    if (outcome.statistics) {
        const SliceStatistics& s = *outcome.statistics;
        j["statistics"] = {
            {"printTimeSeconds", s.print_time_seconds},
            {"filamentUsedMm", s.filament_used_mm},
            {"filamentUsedG", s.filament_used_g},
            {"layerCount", s.layer_count},
            {"gcodeBytes", s.gcode_bytes},
        };
    }
    return j.dump();
}

} // namespace orca::engine::json
