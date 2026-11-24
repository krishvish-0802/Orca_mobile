#pragma once

// What a slice says about the print, gathered from every place libslic3r says
// it: Print::validate() (an error and warnings, each naming its object and often
// a setting), each step's warnings (PrintStateBase::Warning, with the desktop's
// notification type as message_id), the G-code processor's warnings (keys the
// desktop words itself, Plater::get_slice_warning_string) and SlicingErrors.

#include <string>
#include <vector>

#include "libslic3r/Exception.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintBase.hpp"
#include "orca_engine/SliceJob.hpp"

namespace orca::engine::detail {

// Adds `message` unless the same text about the same object is already there.
void add_message(std::vector<SliceMessage>& messages, SliceMessage message);

// A Print::validate() result (its error, or one of its warnings).
SliceMessage validation_message(const Slic3r::StringObjectException& e, SliceMessage::Level level);

// The warnings the print's and its objects' steps left.
void add_step_warnings(const Slic3r::Print& print, std::vector<SliceMessage>& messages);

// The G-code processor's warnings, worded as the desktop words them.
void add_gcode_warnings(const Slic3r::GCodeProcessorResult& result, std::vector<SliceMessage>& messages);

// A SlicingErrors' (or one SlicingError's) per-object reasons.
void add_slicing_errors(const Slic3r::Print& print, const Slic3r::SlicingErrors& errors, std::vector<SliceMessage>& messages);

} // namespace orca::engine::detail
