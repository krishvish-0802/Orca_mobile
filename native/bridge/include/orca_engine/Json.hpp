#pragma once

#include <map>
#include <string>

#include "orca_engine/EngineInfo.hpp"
#include "orca_engine/SliceJob.hpp"

namespace orca::engine::json {

// Serialization used at the JNI boundary, so the Kotlin side parses one
// stable JSON shape instead of mirroring C++ structs through JNI calls.
std::string to_json(const EngineInfo& info);
std::string to_json(const SliceOutcome& outcome);

} // namespace orca::engine::json
