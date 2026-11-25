#include "SliceMessages.hpp"

#include <algorithm>
#include <utility>

#include "libslic3r/Model.hpp"

namespace orca::engine::detail {

namespace {

using Slic3r::PrintStateBase;

// The kind of a message libslic3r gives no id for. Its messages are not
// translated in the engine, so their English text is stable; only the errors
// the app offers a fix for are told apart.
std::string code_of_text(const std::string& text)
{
    static const std::pair<const char*, const char*> known[] = {
        {"empty first layer", "empty_first_layer"},
        {"Levitating objects", "floating"},
        {"No layers were detected", "no_layers"},
        {"It may be too small", "too_small"},
        {"exceeds the maximum build volume height", "too_tall"},
        {"Support enforcers are used but support is not enabled", "support_enforcers"},
        {"Spiral (vase) mode", "spiral_vase"},
        {"Spiral vase mode", "spiral_vase"},
        {"rime tower", "prime_tower"},
        {"Layer height cannot exceed nozzle diameter", "layer_height"},
        {"Line width too", "line_width"},
    };
    for (const auto& [needle, code] : known)
        if (text.find(needle) != std::string::npos)
            return code;
    return {};
}

// The desktop's notification type of a step warning.
std::string code_of_notification(int message_id)
{
    switch (message_id) {
    case PrintStateBase::SlicingNeedSupportOn: return "need_support";
    case PrintStateBase::SlicingReplaceInitEmptyLayers: return "empty_layers";
    case PrintStateBase::SlicingEmptyGcodeLayers: return "empty_layers";
    case PrintStateBase::SlicingGcodeOverlap: return "gcode_overlap";
    case PrintStateBase::SlicingExclusionVolumeToolpath: return "exclusion_area";
    case PrintStateBase::SlicingPreciseSeamWarning: return "precise_seam";
    default: return {};
    }
}

std::string name_of(const Slic3r::ObjectBase* object)
{
    if (const auto* model_object = dynamic_cast<const Slic3r::ModelObject*>(object))
        return model_object->name;
    if (const auto* print_object = dynamic_cast<const Slic3r::PrintObject*>(object))
        return print_object->model_object() != nullptr ? print_object->model_object()->name : std::string();
    return {};
}

std::string trimmed(std::string text)
{
    while (!text.empty() && (text.back() == '\n' || text.back() == ' '))
        text.pop_back();
    return text;
}

SliceMessage step_message(const PrintStateBase::Warning& warning, const std::string& object)
{
    SliceMessage m;
    m.level = SliceMessage::Level::Warning;
    m.text = trimmed(warning.message);
    m.object = object;
    m.code = code_of_notification(warning.message_id);
    if (m.code.empty())
        m.code = code_of_text(m.text);
    return m;
}

} // namespace

void add_message(std::vector<SliceMessage>& messages, SliceMessage message)
{
    if (message.text.empty())
        return;
    const bool known = std::any_of(messages.begin(), messages.end(),
                                   [&](const SliceMessage& m) { return m.text == message.text && m.object == message.object; });
    if (!known)
        messages.push_back(std::move(message));
}

SliceMessage validation_message(const Slic3r::StringObjectException& e, SliceMessage::Level level)
{
    SliceMessage m;
    m.level = level;
    m.text = trimmed(e.string);
    m.object = name_of(e.object);
    m.setting = e.opt_key;
    m.code = code_of_text(m.text);
    return m;
}

void add_step_warnings(const Slic3r::Print& print, std::vector<SliceMessage>& messages)
{
    for (int step = 0; step < int(Slic3r::psCount); ++step)
        for (const PrintStateBase::Warning& w : print.step_state_with_warnings(Slic3r::PrintStep(step)).warnings)
            add_message(messages, step_message(w, {}));
    for (const Slic3r::PrintObject* object : print.objects()) {
        const std::string name = object->model_object() != nullptr ? object->model_object()->name : std::string();
        for (int step = 0; step < int(Slic3r::posCount); ++step)
            for (const PrintStateBase::Warning& w : object->step_state_with_warnings(Slic3r::PrintObjectStep(step)).warnings)
                add_message(messages, step_message(w, name));
    }
}

void add_gcode_warnings(const Slic3r::GCodeProcessorResult& result, std::vector<SliceMessage>& messages)
{
    for (const auto& w : result.warnings) {
        SliceMessage m;
        m.level = SliceMessage::Level::Warning;
        // Plater::get_slice_warning_string, and the filaments a warning names.
        std::string filaments;
        for (const std::string& p : w.params) {
            // The processor numbers filaments from 0.
            std::string n = p;
            try {
                n = std::to_string(std::stoi(p) + 1);
            } catch (const std::exception&) {
            }
            filaments += (filaments.empty() ? "" : ", ") + n;
        }
        if (w.msg == BED_TEMP_TOO_HIGH_THAN_FILAMENT) {
            m.code = "bed_temperature";
            m.text = "The bed is hotter than filament " + filaments +
                     " can take: in a closed printer the nozzle may clog. Open the door or remove the top glass, or lower the bed temperature.";
        } else if (w.msg == NOZZLE_HRC_CHECKER) {
            m.code = "nozzle_hardness";
            m.text = "Filament " + filaments +
                     " needs a harder nozzle than the printer's: fit a hardened nozzle or use another filament, or the nozzle will wear down.";
        } else if (w.msg == NOT_SUPPORT_TRADITIONAL_TIMELAPSE) {
            m.code = "timelapse";
            m.setting = "timelapse_type";
            m.text = "A traditional time-lapse may leave marks on the surface; the smooth mode is recommended.";
        } else if (w.msg == SMOOTH_TIMELAPSE_WITHOUT_PRIME_TOWER) {
            m.code = "timelapse";
            m.setting = "enable_prime_tower";
            m.text = "The smooth time-lapse needs the prime tower, which is off: turn it on and slice again.";
        } else {
            // NOT_GENERATE_TIMELAPSE: the desktop says nothing.
            continue;
        }
        add_message(messages, std::move(m));
    }
}

void add_slicing_errors(const Slic3r::Print& print, const Slic3r::SlicingErrors& errors, std::vector<SliceMessage>& messages)
{
    for (const Slic3r::SlicingError& error : errors.errors_) {
        SliceMessage m;
        m.level = SliceMessage::Level::Error;
        m.text = trimmed(error.what());
        for (const Slic3r::PrintObject* object : print.objects())
            if (object->id().id == error.objectId() && object->model_object() != nullptr)
                m.object = object->model_object()->name;
        m.code = code_of_text(m.text);
        add_message(messages, std::move(m));
    }
}

} // namespace orca::engine::detail
