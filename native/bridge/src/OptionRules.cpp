// How settings depend on each other, as the desktop's settings tabs apply it.
//
// Process settings: ConfigManipulation::toggle_print_fff_options(), generated
// into generated/ProcessOptionRules.inc by tools/generate_option_rules.py, and
// ConfigManipulation::update_print_fff_config() (its dialogs as checks and
// notices), ported below. Filament and printer settings: TabFilament and
// TabPrinter::toggle_options(), ported below without their page gating (every
// page is decided at once).
#include "orca_engine/OptionRules.hpp"

#include <algorithm>
#include <cmath>
#include <memory>
#include <set>
#include <stdexcept>

#include "ConfigBuilder.hpp"
#include "libslic3r/BeltBrim.hpp"
#include "libslic3r/Config.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/libslic3r.h"

namespace orca::engine {

namespace {

using namespace Slic3r;

// What the rules decide, recorded as the desktop's Tab would apply it: the last
// toggle_line() of a setting decides whether it shows, the last toggle_field()
// whether it can be edited.
class Recorder {
public:
    explicit Recorder(SettingsReview& out) : m_out(out) {}

    void field(const std::string& key, bool on, const std::string& reason)
    {
        OptionState& s = m_out.options[key];
        s.enabled = on;
        m_field_reason[key] = on ? std::string() : reason;
        refresh(key);
    }

    void line(const std::string& key, bool on, const std::string& reason)
    {
        OptionState& s = m_out.options[key];
        s.visible = on;
        m_line_reason[key] = on ? std::string() : reason;
        refresh(key);
    }

    void label(const std::string& key, const std::string& text) { m_out.labels[key] = text; }

private:
    void refresh(const std::string& key)
    {
        OptionState& s = m_out.options[key];
        s.reason = !s.visible ? m_line_reason[key] : !s.enabled ? m_field_reason[key] : std::string();
    }

    SettingsReview& m_out;
    std::map<std::string, std::string> m_field_reason;
    std::map<std::string, std::string> m_line_reason;
};

// ConfigManipulation's toggle_print_fff_options() with the context it reads.
class ProcessRules {
public:
    ProcessRules(DynamicPrintConfig& working, const DynamicPrintConfig& printer, bool global, bool bbl, Recorder& recorder)
        : config(&working), printer_config(printer), is_global_config(global), is_BBL_Printer(bbl), m_recorder(recorder)
    {
    }

    void run();

private:
    void toggle_field(const std::string& key, bool on, int = -1, const std::string& reason = {}) { m_recorder.field(key, on, reason); }
    void toggle_line(const std::string& key, bool on, int = -1, const std::string& reason = {}) { m_recorder.line(key, on, reason); }
    void set_option_label(const std::string& key, const std::string& label, int = -1) { m_recorder.label(key, label); }
    // The desktop loads the changed config into the edited preset.
    void apply(DynamicPrintConfig* target, DynamicPrintConfig* changed) { *target = *changed; }

    DynamicPrintConfig* config;
    const DynamicPrintConfig& printer_config;
    // Multi-variant (several nozzle types) presets: the first variant.
    int variant_index = 0;
    bool is_global_config;
    bool is_BBL_Printer;
    Recorder& m_recorder;
};

void ProcessRules::run()
{
#include "generated/ProcessOptionRules.inc"
}

// A value as profiles write it, of a scalar setting or of entry `i` of a list (the last for a shorter list).
std::optional<std::string> value_at(const DynamicPrintConfig& c, const std::string& key, std::size_t i = 0)
{
    const ConfigOption* o = c.option(key);
    if (o == nullptr)
        return std::nullopt;
    if (o->is_vector()) {
        const std::vector<std::string> values = static_cast<const ConfigOptionVectorBase*>(o)->vserialize();
        if (values.empty())
            return std::nullopt;
        return values[std::min(i, values.size() - 1)];
    }
    return o->serialize();
}

bool flag(const DynamicPrintConfig& c, const std::string& key, std::size_t i = 0, bool fallback = false)
{
    const auto v = value_at(c, key, i);
    return v ? (*v == "1" || *v == "true") : fallback;
}

double number(const DynamicPrintConfig& c, const std::string& key, std::size_t i = 0, double fallback = 0)
{
    const auto v = value_at(c, key, i);
    if (!v)
        return fallback;
    try {
        return std::stod(*v);
    } catch (const std::exception&) {
        return fallback;
    }
}

std::string text(const DynamicPrintConfig& c, const std::string& key, std::size_t i = 0)
{
    return value_at(c, key, i).value_or(std::string());
}

// TabFilament::toggle_options(), for filament slot `f`.
void filament_rules(DynamicPrintConfig& config, std::size_t f, bool is_bbl, Recorder& r)
{
    const bool overhang_fan = flag(config, "enable_overhang_bridge_fan", f);
    for (const char* el : {"overhang_fan_speed", "overhang_fan_threshold", "internal_bridge_fan_speed"})
        r.field(el, overhang_fan, "Enable cooling for overhangs and bridges to edit this setting.");
    r.field("dont_slow_down_outer_wall", flag(config, "slow_down_for_layer_cooling", f),
            "Enable slowing down for layer cooling to use this setting.");

    // A first layer fan speed only means something when no layers are kept without cooling; the
    // desktop then also clears the override.
    const bool first_layer_fan = number(config, "close_fan_the_first_x_layers", f) <= 0;
    r.line("initial_layer_fan_speed", first_layer_fan,
           "Set the number of initial layers without cooling to zero to override the first layer fan speed.");
    if (!first_layer_fan) {
        if (auto* opt = dynamic_cast<ConfigOptionInts*>(config.option("initial_layer_fan_speed"));
            opt != nullptr && std::any_of(opt->values.begin(), opt->values.end(), [](int v) { return v != -1; }))
            config.set_key_value("initial_layer_fan_speed", new ConfigOptionInts(std::vector<int>(opt->values.size(), -1)));
    }

    r.line("additional_cooling_fan_speed", flag(config, "auxiliary_fan"), "Enable the auxiliary fan in the printer profile to use this setting.");
    const bool air_filtration = flag(config, "support_air_filtration");
    for (const char* el : {"activate_air_filtration", "during_print_exhaust_fan_speed", "complete_print_exhaust_fan_speed"})
        r.line(el, air_filtration, "The selected printer does not support air filtration.");
    if (air_filtration) {
        const bool active = flag(config, "activate_air_filtration", f);
        const std::string reason = "Activate air filtration to edit this setting.";
        r.field("activate_air_filtration_during_print", active, reason);
        r.field("during_print_exhaust_fan_speed", active && flag(config, "activate_air_filtration_during_print", f),
                "Activate air filtration and enable it during printing to edit the exhaust fan speed.");
        r.field("activate_air_filtration_on_completion", active, reason);
        r.field("complete_print_exhaust_fan_speed", active && flag(config, "activate_air_filtration_on_completion", f),
                "Activate air filtration and enable it on completion to edit the exhaust fan speed.");
    }

    const bool pa = flag(config, "enable_pressure_advance", f);
    const std::string pa_reason = "Enable pressure advance to use this setting.";
    r.field("pressure_advance", pa, pa_reason);

    // Plate temperatures: every plate type's on a printer that takes several, else the current plate's.
    static const char* bed_temp_keys[] = {"supertack_plate_temp_initial_layer", "cool_plate_temp_initial_layer",
                                          "textured_cool_plate_temp_initial_layer", "eng_plate_temp_initial_layer",
                                          "textured_plate_temp_initial_layer", "hot_plate_temp_initial_layer"};
    std::string current_key;
    if (const auto* bed = config.option<ConfigOptionEnum<BedType>>("curr_bed_type"))
        current_key = get_bed_temp_1st_layer_key(bed->value);
    const bool known = std::find(std::begin(bed_temp_keys), std::end(bed_temp_keys), current_key) != std::end(bed_temp_keys);
    const bool multi_bed = !known || is_bbl || flag(config, "support_multi_bed_types");
    for (const char* key : bed_temp_keys)
        r.line(key, multi_bed || current_key == key,
               "Select this bed type or enable support for multiple bed types in the printer profile to show its temperature settings.");

    r.field("adaptive_pressure_advance", pa, pa_reason);
    r.field("adaptive_pressure_advance_overhangs", pa, pa_reason);
    const bool adaptive_pa = pa && flag(config, "adaptive_pressure_advance", f);
    const std::string adaptive_reason = "Enable pressure advance and adaptive pressure advance to use this setting.";
    for (const char* el : {"adaptive_pressure_advance_overhangs", "adaptive_pressure_advance_model", "adaptive_pressure_advance_bridges"})
        r.line(el, adaptive_pa, adaptive_reason);

    const bool pellet = flag(config, "pellet_modded_printer");
    r.line("pellet_flow_coefficient", pellet, "Enable the pellet printer option in the printer profile to use this setting.");
    r.line("filament_diameter", !pellet, "Filament diameter is unavailable for pellet printers.");
    r.line("activate_chamber_temp_control", flag(config, "support_chamber_temp_control"),
           "The selected printer does not support chamber temperature control.");
    if (config.has("volumetric_speed_coefficients"))
        r.field("filament_adaptive_volumetric_speed", text(config, "volumetric_speed_coefficients", f) != "0 0 0 0 0 0",
                "Set nonzero volumetric speed coefficients before enabling adaptive volumetric speed.");

    const std::string bambu = "This setting is unavailable for Bambu Lab printers.";
    r.field("filament_minimal_purge_on_wipe_tower", !is_bbl, bambu);
    const bool type1_tower = is_bbl || text(config, "wipe_tower_type") == "type1";
    bool multiple_nozzles = false;
    if (const auto* counts = config.option<ConfigOptionIntsNullable>("extruder_max_nozzle_count"))
        multiple_nozzles = std::any_of(counts->values.begin(), counts->values.end(),
                                       [](int v) { return v > 1 && v != ConfigOptionIntsNullable::nil_value(); });
    r.line("filament_prime_volume", type1_tower, "Select wipe tower Type 1 in the printer settings to use this setting.");
    r.line("filament_prime_volume_nc", type1_tower && multiple_nozzles,
           "Select wipe tower Type 1 on a printer whose extruder holds more than one nozzle to use this setting.");
    for (const char* el : {"filament_loading_speed_start", "filament_loading_speed", "filament_unloading_speed_start", "filament_unloading_speed",
                           "filament_toolchange_delay", "filament_cooling_moves", "filament_cooling_initial_speed", "filament_cooling_final_speed"})
        r.field(el, !is_bbl, bambu);
    const bool ramming = flag(config, "filament_multitool_ramming", f);
    r.field("filament_multitool_ramming_volume", ramming, "Enable multitool ramming to edit this setting.");
    r.field("filament_multitool_ramming_flow", ramming, "Enable multitool ramming to edit this setting.");
    const auto* nozzles = config.option<ConfigOptionFloats>("nozzle_diameter");
    const bool bbl_multi = is_bbl && nozzles != nullptr && nozzles->size() > 1;
    r.line("long_retractions_when_ec", bbl_multi, "This setting requires a Bambu Lab printer with multiple extruders.");
    r.line("retraction_distances_when_ec", bbl_multi && flag(config, "long_retractions_when_ec", f),
           "Use a Bambu Lab printer with multiple extruders and enable long retraction when the extruder changes.");
}

// TabPrinter::toggle_options(), for the first extruder.
void printer_rules(DynamicPrintConfig& config, bool is_bbl, Recorder& r)
{
    for (const char* el : {"scan_first_layer", "bbl_calib_mark_logo", "bbl_use_printhost"})
        r.line(el, is_bbl, "This setting requires a Bambu Lab printer.");
    for (const char* el : {"use_firmware_retraction", "use_relative_e_distances", "support_multi_bed_types", "pellet_modded_printer",
                           "bed_mesh_max", "bed_mesh_min", "bed_mesh_probe_distance", "adaptive_bed_mesh_margin", "thumbnails"})
        r.line(el, !is_bbl, "This setting is unavailable for Bambu Lab printers.");

    const std::string flavor = text(config, "gcode_flavor");
    const bool marlin2 = flavor == "marlin2";
    const bool marlin_legacy = flavor == "marlin";
    const bool klipper = flavor == "klipper";
    const bool reprap = flavor == "reprapfirmware";
    r.line("enable_power_loss_recovery", is_bbl || marlin2, "Power loss recovery requires a Bambu Lab printer or the Marlin firmware G-code flavor.");

    const bool belt = flag(config, "belt_printer");
    for (const char* el : {"build_plate_tilt_x", "build_plate_tilt_y"})
        r.field(el, !belt, "Build plate tilt is derived from the belt tilt on belt printers.");
    const std::string belt_reason = "Enable belt printer mode to use this setting.";
    for (const char* el : {"belt_printer_infinite_y", "belt_slice_rotation_angle", "belt_slice_rotation", "gcode_remap_x", "gcode_remap_y",
                           "gcode_remap_z", "belt_support_floor_offset", "belt_frame_tilt_decouple"})
        r.line(el, belt, belt_reason);
    r.field("belt_slice_rotation_angle", belt && text(config, "belt_slice_rotation") != "none",
            "Enable belt printer mode and select a belt rotation axis to edit its angle.");
    r.line("belt_frame_tilt_angle", belt && flag(config, "belt_frame_tilt_decouple"),
           "Enable belt printer mode and decouple the machine frame tilt to edit its angle.");
    r.line("parallel_printheads_count", flag(config, "support_parallel_printheads"), "Enable support for parallel printheads to edit their count.");

    const auto* nozzles = config.option<ConfigOptionFloats>("nozzle_diameter");
    const std::size_t extruders = nozzles != nullptr ? nozzles->size() : 1;
    const bool per_extruder_exclusion = text(config, "bed_exclude_volume_mode") == "per_extruder";
    r.line("bed_exclude_volume_mode", extruders > 1, "Collision volume mode selection requires multiple extruders.");
    r.line("bed_exclude_volumes", extruders <= 1 || !per_extruder_exclusion,
           "Select a shared collision volume mode or use a single-extruder printer to edit this setting.");
    r.line("fan_direction", flag(config, "auxiliary_fan"), "Enable the auxiliary fan to edit its direction.");
    r.line("support_air_filtration", !flag(config, "support_cooling_filter"),
           "Air filtration and the cooling filter are alternative accessories; disable cooling filter support to use air filtration.");
    r.line("cooling_filter_enabled", flag(config, "support_cooling_filter"), "Enable cooling filter support in the printer profile to use this setting.");
    r.line("wrapping_detection_gcode", false, "The selected printer does not support wrapping detection.");

    // Multimaterial.
    r.line("enable_belt_purge_tower", belt, "Enable belt printer mode to use the belt purge tower.");
    const bool tower2 = !is_bbl && text(config, "wipe_tower_type") == "type2";
    r.line("wipe_tower_type", !is_bbl && !belt, "Wipe tower type selection is unavailable for Bambu Lab printers and belt printers.");
    for (const char* el : {"enable_filament_ramming", "cooling_tube_retraction", "cooling_tube_length", "parking_pos_retraction", "extra_loading_move",
                           "high_current_on_filament_swap"})
        r.field(el, tower2, "Select wipe tower Type 2 on a non-Bambu printer to use this setting.");
    const bool semm = flag(config, "single_extruder_multi_material");
    if (!semm && flag(config, "manual_filament_change"))
        config.set_key_value("manual_filament_change", new ConfigOptionBool(false));
    r.field("extruders_count", !semm, "Extruder count cannot be edited in single extruder multimaterial mode.");
    r.field("manual_filament_change", semm, "Enable single extruder multimaterial mode to use manual filament changes.");
    r.field("purge_in_prime_tower", semm && tower2, "Enable single extruder multimaterial mode with wipe tower Type 2 on a non-Bambu printer to use this setting.");
    const bool imex = flag(config, "is_imex");
    for (const char* el : {"imex_gantry_count", "imex_tools_per_gantry", "imex_nozzle_clearance_x", "imex_nozzle_clearance_y", "imex_carriage_margin",
                           "imex_tool_layout", "imex_viz_theme", "imex_firmware_managed_zones"})
        r.field(el, imex, "Enable IDEX/IQEX printer mode to use this setting.");
    const std::string tool_tower = "Use multiple extruders with wipe tower Type 2 on a non-Bambu printer and disable single extruder multimaterial mode.";
    r.field("tool_change_on_wipe_tower", !semm && tower2 && extruders > 1, tool_tower);
    r.field("wait_for_temp_on_wipe_tower", !semm && tower2 && extruders > 1, tool_tower);

    // Extruder.
    const std::string geometry = "The extruder printable area and height are fixed by the printer profile.";
    const std::string dual = "This setting is shown for printers with exactly two extruders.";
    r.field("extruder_printable_area", false, geometry);
    r.line("extruder_printable_area", extruders == 2, dual);
    r.field("extruder_printable_height", false, geometry);
    r.line("extruder_printable_height", extruders == 2, dual);
    r.line("extruder_bed_exclude_volumes", extruders > 1 && per_extruder_exclusion,
           "Select per-extruder collision volumes on a printer with multiple extruders to edit this setting.");
    const bool firmware_retraction = flag(config, "use_firmware_retraction");
    const bool retract_length = number(config, "retraction_length") > 0;
    const bool retraction = retract_length || firmware_retraction;
    const std::string retraction_reason = "Set retraction length above zero or enable firmware retraction to use this setting.";
    r.field("retraction_length", !firmware_retraction, "Retraction length is controlled by the firmware while firmware retraction is enabled.");
    r.field("retraction_minimum_travel", retraction, retraction_reason);
    for (const char* el : {"z_hop", "retract_when_changing_layer"})
        r.field(el, retraction, retraction_reason);
    for (const char* el : {"retract_lift_above", "retract_lift_below", "retract_lift_enforce"})
        r.field(el, retraction && number(config, "z_hop") > 0, "Enable retraction and set Z hop above zero to use this setting.");
    for (const char* el : {"retraction_speed", "deretraction_speed", "retract_before_wipe", "retract_after_wipe", "retract_restart_extra", "wipe_distance"})
        r.field(el, retraction && !firmware_retraction, "Set retraction length above zero and disable firmware retraction to edit this setting.");
    const bool wipe = retraction && flag(config, "wipe");
    const double before = number(config, "retract_before_wipe", 0, 100);
    const double after = number(config, "retract_after_wipe", 0, 0);
    if (!firmware_retraction) {
        r.field("retract_before_wipe", wipe && std::abs(after - 100.) > 1e-6,
                "Enable retraction and wiping, and set retract after wipe below 100% to edit retract before wipe.");
        r.field("retract_after_wipe", wipe && std::abs(before - 100.) > 1e-6,
                "Enable retraction and wiping, and set retract before wipe below 100% to edit retract after wipe.");
    }
    r.field("wipe_distance", wipe, "Enable retraction and wiping to edit wipe distance.");
    const bool toolchange_retraction = number(config, "retract_length_toolchange") > 0;
    r.field("retract_restart_extra_toolchange", toolchange_retraction,
            "Use multiple extruders and set tool change retraction length above zero to edit this setting.");
    r.field("long_retractions_when_cut", !firmware_retraction && number(config, "enable_long_retraction_when_cut") != 0,
            "Disable firmware retraction and allow long retraction when cutting to use this setting.");
    r.line("retraction_distances_when_cut", flag(config, "long_retractions_when_cut"), "Enable long retraction when cutting to edit the retraction distance.");
    r.field("travel_slope", text(config, "z_hop_types") != "Normal Lift", "Select a Z hop type other than Normal to edit travel slope.");

    // Motion ability.
    r.field("machine_max_acceleration_travel", !marlin_legacy && !klipper,
            "Travel acceleration limits are unavailable for the Marlin legacy and Klipper G-code flavors.");
    r.field("machine_max_junction_deviation", marlin2, "This setting requires the Marlin firmware G-code flavor.");
    bool jerk = !marlin2;
    if (marlin2) {
        const auto* jd = config.option<ConfigOptionFloats>("machine_max_junction_deviation");
        jerk = jd == nullptr || std::all_of(jd->values.begin(), jd->values.end(), [](double v) { return v == 0.0; });
    }
    for (const char* el : {"machine_max_jerk_x", "machine_max_jerk_y", "machine_max_jerk_z", "machine_max_jerk_e"})
        r.field(el, jerk, "Jerk limits are unavailable while Marlin uses nonzero junction deviation.");
    r.field("emit_machine_limits_to_gcode", marlin_legacy || marlin2 || reprap,
            "Emitting machine limits requires the Marlin legacy, Marlin or RepRapFirmware G-code flavor.");
    const bool resonance = flag(config, "resonance_avoidance");
    r.field("min_resonance_avoidance_speed", resonance, "Enable resonance avoidance to edit its speed range.");
    r.field("max_resonance_avoidance_speed", resonance, "Enable resonance avoidance to edit its speed range.");
    const bool shaping = marlin2 || reprap;
    for (const char* el : {"input_shaping_emit", "input_shaping_type", "input_shaping_freq_x", "input_shaping_freq_y", "input_shaping_damp_x",
                           "input_shaping_damp_y"})
        r.line(el, shaping, "This input shaping setting requires the Marlin or RepRapFirmware G-code flavor.");
    if (shaping) {
        const bool emit = flag(config, "emit_machine_limits_to_gcode");
        r.field("input_shaping_emit", emit, "Enable emitting machine limits to G-code to emit input shaping settings.");
        const bool emitting = emit && flag(config, "input_shaping_emit");
        const std::string reason = "Enable emitting machine limits and input shaping to G-code to edit this setting.";
        const std::string y_reason =
            "Enable emitting machine limits and input shaping to G-code with the Marlin firmware G-code flavor to edit separate Y-axis values.";
        r.field("input_shaping_type", emitting, reason);
        r.field("input_shaping_freq_x", emitting, reason);
        r.field("input_shaping_damp_x", emitting, reason);
        r.field("input_shaping_freq_y", emitting && !reprap, y_reason);
        r.field("input_shaping_damp_y", emitting && !reprap, y_reason);
    }
}

template <class Option, class Value> std::string serialized(Value v) { return Option(v).serialize(); }

// ConfigManipulation::update_print_fff_config(): what the desktop resets with a
// notice, sets silently, or asks about. Resets go into `config` (and `notices`);
// questions into `checks`.
void update_rules(DynamicPrintConfig& config, SettingsScope scope, std::size_t filaments, bool is_bbl, SettingsReview& out)
{
    const bool global = scope == SettingsScope::Global;
    const bool object = scope == SettingsScope::Object;
    auto notice = [&](const std::string& message) { out.notices.push_back(message); };

    if (flag(config, "belt_printer") && (number(config, "raft_layers") > 0 || text(config, "draft_shield") != "disabled")) {
        notice("Raft and draft shield are not available on belt printers. They have been turned off.");
        config.set_key_value("raft_layers", new ConfigOptionInt(0));
        config.set_key_value("draft_shield", new ConfigOptionEnum<DraftShield>(dsDisabled));
    }
    if (config.has("layer_height") && config.opt_float("layer_height") < EPSILON) {
        notice("Layer height can't be zero. It has been reset to 0.2 mm.");
        config.set_key_value("layer_height", new ConfigOptionFloat(0.2));
    }
    for (const char* key : {"ironing_spacing", "support_ironing_spacing"})
        if (config.has(key) && config.opt_float(key) < IRONING_SPACING_MIN) {
            notice("Ironing spacing is too small. It has been reset to 0.1 mm.");
            config.set_key_value(key, new ConfigOptionFloat(0.1));
        }
    if (config.has("initial_layer_print_height") && config.opt_float("initial_layer_print_height") < EPSILON) {
        notice("The first layer height can't be zero. It has been reset to 0.2 mm.");
        config.set_key_value("initial_layer_print_height", new ConfigOptionFloat(0.2));
    }
    for (const char* key : {"xy_hole_compensation", "xy_contour_compensation"})
        if (config.has(key) && std::abs(config.opt_float(key)) > 2) {
            notice("Hole and contour compensation are for small corrections of a model's size (up to 2 mm); use Scale for larger "
                   "changes. It has been reset to 0.");
            config.set_key_value(key, new ConfigOptionFloat(0));
        }
    if (config.has("elefant_foot_compensation") && config.opt_float("elefant_foot_compensation") > 1) {
        notice("The elephant foot compensation is too large (over 1 mm). If the first layers spread a lot, check other settings "
               "such as the bed temperature. It has been reset to 0.");
        config.set_key_value("elefant_foot_compensation", new ConfigOptionFloat(0));
    }
    // Wrapping detection needs a printer the desktop's device database knows.
    if (flag(config, "enable_wrapping_detection"))
        config.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));

    // Spiral vase needs a single wall and nothing inside.
    if (scope != SettingsScope::Plate && flag(config, "spiral_mode") &&
        !(number(config, "wall_loops") == 1 && number(config, "top_shell_layers") == 0 && number(config, "sparse_infill_density") == 0 &&
          !flag(config, "enable_support") && number(config, "enforce_support_layers") == 0 && !flag(config, "detect_thin_wall") &&
          !flag(config, "overhang_reverse") && text(config, "timelapse_type") == "0" && !flag(config, "enable_wrapping_detection"))) {
        SettingsCheck check;
        check.id = "spiral_mode";
        check.message = "Spiral vase mode only works with one wall, no supports, no top layers, 0% infill, and a traditional time-lapse.";
        check.fixes.push_back({"Change those settings for spiral vase",
                               {{"wall_loops", "1"},
                                {"top_shell_layers", "0"},
                                {"sparse_infill_density", "0%"},
                                {"enable_support", "0"},
                                {"enforce_support_layers", "0"},
                                {"detect_thin_wall", "0"},
                                {"overhang_reverse", "0"},
                                {"timelapse_type", serialized<ConfigOptionEnum<TimelapseType>>(tlTraditional)},
                                {"enable_wrapping_detection", "0"}}});
        check.fixes.push_back({"Turn spiral vase off", {{"spiral_mode", "0"}}});
        out.checks.push_back(std::move(check));
    }

    if (flag(config, "alternate_extra_wall") && text(config, "ensure_vertical_shell_thickness") == "ensure_all") {
        const std::string moderate = serialized<ConfigOptionEnum<EnsureVerticalShellThickness>>(evstModerate);
        if (global) {
            SettingsCheck check;
            check.id = "alternate_extra_wall";
            check.message = "An alternate extra wall doesn't work well when vertical shell thickness is ensured everywhere (All).";
            check.fixes.push_back({"Use Moderate shell thickness", {{"ensure_vertical_shell_thickness", moderate}, {"alternate_extra_wall", "1"}}});
            check.fixes.push_back({"Turn the alternate extra wall off", {{"alternate_extra_wall", "0"}}});
            out.checks.push_back(std::move(check));
        } else {
            notice("An alternate extra wall doesn't work well when vertical shell thickness is ensured everywhere; it has been set to Moderate.");
            config.set_key_value("ensure_vertical_shell_thickness", new ConfigOptionEnum<EnsureVerticalShellThickness>(evstModerate));
        }
    }

    // Bambu Lab printers do not print a cone tower.
    if (is_bbl && flag(config, "enable_prime_tower") && text(config, "wipe_tower_wall_type") == "cone")
        config.set_key_value("wipe_tower_wall_type", new ConfigOptionEnum<WipeTowerWallType>(WipeTowerWallType::wtwRectangle));

    // Supports go with detected overhang walls.
    if (global && flag(config, "enable_support") && !flag(config, "detect_overhang_wall"))
        config.set_key_value("detect_overhang_wall", new ConfigOptionBool(true));

    // A style of the other support type goes back to the default.
    if (flag(config, "enable_support")) {
        if (const auto* type = config.option<ConfigOptionEnum<SupportType>>("support_type")) {
            if (const auto* style = config.option<ConfigOptionEnum<SupportMaterialStyle>>("support_style")) {
                const std::set<int> normal = {smsDefault, smsGrid, smsSnug};
                const std::set<int> tree = {smsDefault, smsTreeSlim, smsTreeStrong, smsTreeHybrid, smsTreeOrganic};
                const auto& allowed = is_tree(type->value) ? tree : normal;
                if (allowed.count(style->value) == 0)
                    config.set_key_value("support_style", new ConfigOptionEnum<SupportMaterialStyle>(smsDefault));
            }
        }
    }

    // A feature's filament past the filaments loaded goes back to the object's own.
    for (const char* key : {"support_filament", "support_interface_filament", "brim_filament_id", "wipe_tower_filament", "outer_wall_filament_id",
                            "inner_wall_filament_id", "sparse_infill_filament_id", "internal_solid_filament_id", "top_surface_filament_id",
                            "bottom_surface_filament_id"})
        if (auto* opt = dynamic_cast<ConfigOptionInt*>(config.option(key)); opt != nullptr && opt->value > int(filaments))
            config.set_key_value(key, new ConfigOptionInt(0));

    if (config.has("seam_slope_type") && text(config, "seam_slope_type") != "none" && config.has("layer_height") &&
        config.get_abs_value("seam_slope_start_height") >= config.opt_float("layer_height")) {
        notice("The scarf seam's start height must be lower than the layer height. It has been reset to 0.");
        config.set_key_value("seam_slope_start_height", new ConfigOptionFloatOrPercent(0, false));
    }
    if (config.has("infill_lock_depth") && config.has("skin_infill_depth") &&
        config.opt_float("infill_lock_depth") > config.opt_float("skin_infill_depth")) {
        notice("The lock depth must be smaller than the skin depth. It has been reset to half the skin depth.");
        config.set_key_value("infill_lock_depth", new ConfigOptionFloat(config.opt_float("skin_infill_depth") / 2));
    }

    if (config.has("fuzzy_skin_mode") && text(config, "fuzzy_skin_mode") != "displacement" && text(config, "wall_generator") != "arachne") {
        SettingsCheck check;
        check.id = "fuzzy_skin_mode";
        check.message = "The Extrusion and Combined fuzzy skin modes need the Arachne wall generator.";
        check.fixes.push_back({"Use the Arachne wall generator",
                               {{"wall_generator", serialized<ConfigOptionEnum<PerimeterGeneratorType>>(PerimeterGeneratorType::Arachne)}}});
        check.fixes.push_back({"Use Displacement fuzzy skin",
                               {{"fuzzy_skin_mode", serialized<ConfigOptionEnum<FuzzySkinMode>>(FuzzySkinMode::Displacement)}}});
        out.checks.push_back(std::move(check));
    }
    (void)object;
}

} // namespace

SettingsReview review_settings(const std::optional<PresetSelection>& presets, const std::map<std::string, std::string>& overrides,
                               SettingsScope scope, int filament, PresetCatalog* catalog)
{
    const DynamicPrintConfig original = ConfigBuilder::resolve(presets, overrides, catalog);
    DynamicPrintConfig working = original;
    const bool is_bbl = presets && presets->vendor == "BBL";
    const std::size_t filaments = presets ? std::max<std::size_t>(presets->filaments.size(), 1) : 1;

    SettingsReview out;
    update_rules(working, scope, filaments, is_bbl, out);
    Recorder recorder(out);
    ProcessRules(working, original, scope == SettingsScope::Global, is_bbl, recorder).run();
    if (scope == SettingsScope::Global) {
        filament_rules(working, std::size_t(std::max(filament, 0)), is_bbl, recorder);
        printer_rules(working, is_bbl, recorder);
    }

    // What the rules set: every value that differs from the settings as given.
    for (const std::string& key : working.keys()) {
        const ConfigOption* before = original.option(key);
        const ConfigOption* after = working.option(key);
        if (after != nullptr && (before == nullptr || !(*before == *after)))
            out.changes[key] = after->serialize();
    }
    return out;
}

} // namespace orca::engine
