#include "orca_engine/Json.hpp"

#include <stdexcept>

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

std::string to_json(const std::vector<std::string>& names)
{
    return nlohmann::json(names).dump();
}

std::string to_json(const CompatiblePresets& presets)
{
    nlohmann::json j{
        {"processes", presets.processes},
        {"filaments", presets.filaments},
        {"defaultProcess", presets.default_process},
        {"defaultFilament", presets.default_filament},
    };
    return j.dump();
}

std::string to_json(const BedShape& bed)
{
    nlohmann::json outline = nlohmann::json::array();
    for (const auto& point : bed.outline)
        outline.push_back({point[0], point[1]});
    return nlohmann::json{{"outline", outline}, {"maxHeight", bed.max_height}}.dump();
}

std::string to_json(const std::vector<ObjectSummary>& objects)
{
    nlohmann::json list = nlohmann::json::array();
    for (const ObjectSummary& object : objects)
        list.push_back({
            {"name", object.name},
            {"instances", object.instance_count},
            {"triangles", object.triangle_count},
            {"size", {object.size[0], object.size[1], object.size[2]}},
        });
    return nlohmann::json{{"objects", list}}.dump();
}

std::string to_json(const PreviewSummary& summary)
{
    return nlohmann::json{
        {"layerZs", summary.layer_zs},
        {"printTimeSeconds", summary.print_time_seconds},
        {"moveCount", summary.move_count},
        {"filamentColors", summary.filament_colors},
    }.dump();
}

std::optional<PresetSelection> preset_selection_from_json(const std::string& payload)
{
    if (payload.empty())
        return std::nullopt;
    try {
        const nlohmann::json j = nlohmann::json::parse(payload);
        PresetSelection selection;
        selection.vendor = j.at("vendor").get<std::string>();
        selection.printer = j.at("printer").get<std::string>();
        selection.process = j.at("process").get<std::string>();
        selection.filaments = j.at("filaments").get<std::vector<std::string>>();
        return selection;
    } catch (const nlohmann::json::exception& e) {
        throw std::runtime_error(std::string("Malformed preset selection: ") + e.what());
    }
}

std::string error_json(const std::string& message)
{
    return nlohmann::json{{"error", message}}.dump();
}

} // namespace orca::engine::json
