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
            {"plates", object.instance_plates},
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

std::string to_json(const std::vector<OptionInfo>& options)
{
    nlohmann::json list = nlohmann::json::array();
    for (const OptionInfo& option : options) {
        nlohmann::json item = {
            {"key", option.key},
            {"type", option.type},
            {"label", option.label},
            {"fullLabel", option.full_label},
            {"category", option.category},
            {"tooltip", option.tooltip},
            {"unit", option.unit},
            {"mode", option.mode},
            {"default", option.default_value},
            {"enumValues", option.enum_values},
            {"enumLabels", option.enum_labels},
            {"nullable", option.nullable},
            {"multiline", option.multiline},
        };
        if (option.min)
            item["min"] = *option.min;
        if (option.max)
            item["max"] = *option.max;
        list.push_back(std::move(item));
    }
    return nlohmann::json{{"options", list}}.dump();
}

std::string to_json(const PresetOptionKeys& keys)
{
    return nlohmann::json{{"process", keys.process}, {"filament", keys.filament}, {"printer", keys.printer}}.dump();
}

std::string values_to_json(const std::map<std::string, std::string>& values)
{
    return nlohmann::json{{"values", values}}.dump();
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
        selection.filament_colours = j.value("filamentColours", std::vector<std::string>{});
        return selection;
    } catch (const nlohmann::json::exception& e) {
        throw std::runtime_error(std::string("Malformed preset selection: ") + e.what());
    }
}

std::string to_json(const PlateChange& change, const std::vector<ObjectSummary>& objects)
{
    nlohmann::json result = nlohmann::json::parse(to_json(objects));
    result["placed"] = change.placed;
    result["notPlaced"] = change.not_placed;
    return result.dump();
}

namespace {

nlohmann::json transform_json(const InstanceTransform& transform)
{
    return {
        {"position", transform.position},
        {"rotation", transform.rotation_deg},
        {"scale", transform.scale},
        {"size", transform.size},
        {"mirrored", transform.mirrored},
    };
}

} // namespace

std::string to_json(const ObjectDetails& details, const std::vector<InstanceTransform>& volume_transforms,
                    const std::vector<ObjectSummary>& objects)
{
    nlohmann::json volumes = nlohmann::json::array();
    for (std::size_t i = 0; i < details.volumes.size(); ++i) {
        const VolumeInfo& volume = details.volumes[i];
        nlohmann::json entry = {
            {"name", volume.name},
            {"kind", static_cast<int>(volume.kind)},
            {"triangles", volume.triangle_count},
            {"settings", volume.settings},
            {"embossed", volume.embossed},
        };
        if (i < volume_transforms.size())
            entry["transform"] = transform_json(volume_transforms[i]);
        volumes.push_back(std::move(entry));
    }
    nlohmann::json ranges = nlohmann::json::array();
    for (const LayerRangeInfo& range : details.layer_ranges)
        ranges.push_back({{"min", range.min_z}, {"max", range.max_z}, {"settings", range.settings}});
    nlohmann::json result = nlohmann::json::parse(to_json(objects));
    result["object"] = {
        {"name", details.name},
        {"settings", details.settings},
        {"volumes", volumes},
        {"layerRanges", ranges},
    };
    return result.dump();
}

std::string to_json(const InstanceTransform& transform, const std::vector<ObjectSummary>& objects)
{
    nlohmann::json result = nlohmann::json::parse(to_json(objects));
    result["transform"] = transform_json(transform);
    return result.dump();
}

ArrangeOptions arrange_options_from_json(const std::string& payload)
{
    ArrangeOptions options;
    if (payload.empty())
        return options;
    try {
        const nlohmann::json j = nlohmann::json::parse(payload);
        options.spacing_mm = j.value("spacingMm", options.spacing_mm);
        options.allow_rotation = j.value("allowRotation", options.allow_rotation);
        options.align_to_y_axis = j.value("alignToYAxis", options.align_to_y_axis);
        return options;
    } catch (const nlohmann::json::exception& e) {
        throw std::runtime_error(std::string("Malformed arrange options: ") + e.what());
    }
}

std::string error_json(const std::string& message)
{
    return nlohmann::json{{"error", message}}.dump();
}

} // namespace orca::engine::json
