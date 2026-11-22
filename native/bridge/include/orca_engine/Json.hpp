#pragma once

#include <map>
#include <optional>
#include <string>
#include <vector>

#include "orca_engine/ConfigOptions.hpp"
#include "orca_engine/EngineInfo.hpp"
#include "orca_engine/GCodePreview.hpp"
#include "orca_engine/PlateTools.hpp"
#include "orca_engine/PresetCatalog.hpp"
#include "orca_engine/Project.hpp"
#include "orca_engine/SliceJob.hpp"

namespace orca::engine::json {

// Serialization used at the JNI boundary, so the Kotlin side parses one
// stable JSON shape instead of mirroring C++ structs through JNI calls.
std::string to_json(const EngineInfo& info);
std::string to_json(const SliceOutcome& outcome);
std::string to_json(const std::vector<std::string>& names);
std::string to_json(const CompatiblePresets& presets);
std::string to_json(const BedShape& bed);
std::string to_json(const std::vector<ObjectSummary>& objects);
std::string to_json(const PreviewSummary& summary);
std::string to_json(const std::vector<OptionInfo>& options);
std::string to_json(const PresetOptionKeys& keys);
// {"placed":n,"notPlaced":n,"objects":[...]}: a plate tool's outcome and the plate after it.
std::string to_json(const PlateChange& change, const std::vector<ObjectSummary>& objects);
// {"transform":{"position":[..],"rotation":[..],"scale":[..],"size":[..],"mirrored":[..]},"objects":[...]}
std::string to_json(const InstanceTransform& transform, const std::vector<ObjectSummary>& objects);
// {"values":{key:value}}
std::string values_to_json(const std::map<std::string, std::string>& values);

// Parses {"vendor":..,"printer":..,"process":..,"filaments":[..]}. An empty
// payload means no presets. Throws std::runtime_error on malformed input.
std::optional<PresetSelection> preset_selection_from_json(const std::string& payload);

// Parses {"spacingMm":..,"allowRotation":..,"alignToYAxis":..}; missing fields
// keep their defaults. Throws std::runtime_error on malformed input.
ArrangeOptions arrange_options_from_json(const std::string& payload);

// {"error": message}, for calls that fail before producing their own result.
std::string error_json(const std::string& message);

} // namespace orca::engine::json
