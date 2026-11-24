#include "orca_engine/PresetFiles.hpp"

#include <algorithm>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <sstream>
#include <stdexcept>

#include "PresetCatalogImpl.hpp"
#include "libslic3r/Config.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r_version.h"

namespace orca::engine {

using Slic3r::ConfigOption;
using Slic3r::ConfigOptionVectorBase;
using Slic3r::DynamicPrintConfig;
using Slic3r::Preset;
using Slic3r::PresetBundle;

namespace {

Slic3r::PresetCollection& collection_of(PresetBundle& bundle, PresetKind kind)
{
    switch (kind) {
    case PresetKind::Printer: return bundle.printers;
    case PresetKind::Process: return bundle.prints;
    default: return bundle.filaments;
    }
}

const char* settings_id_key(PresetKind kind)
{
    switch (kind) {
    case PresetKind::Printer: return "printer_settings_id";
    case PresetKind::Process: return "print_settings_id";
    default: return "filament_settings_id";
    }
}

const std::vector<std::string>& keys_of(PresetKind kind)
{
    static const std::vector<std::string> printer = Preset::printer_options();
    static const std::vector<std::string> process = Preset::print_options();
    static const std::vector<std::string> filament = Preset::filament_options();
    return kind == PresetKind::Printer ? printer : kind == PresetKind::Process ? process : filament;
}

// What names and links presets rather than setting anything.
bool is_bookkeeping(const std::string& key)
{
    static const std::set<std::string> keys = {
        "inherits", "print_settings_id", "printer_settings_id", "filament_settings_id", "compatible_printers",
        "compatible_printers_condition", "compatible_prints", "compatible_prints_condition", "different_settings_to_system",
        "inherits_group", "filament_ids", "print_compatible_printers"};
    return keys.count(key) > 0;
}

// Element `i` (or the last of a shorter list) of a per-filament option, as one filament's preset holds it.
std::unique_ptr<ConfigOption> element_of(const ConfigOption& option, std::size_t i)
{
    std::unique_ptr<ConfigOption> out(option.clone());
    if (!option.is_vector())
        return out;
    const auto& values = static_cast<const ConfigOptionVectorBase&>(option);
    if (values.size() <= 1)
        return out;
    auto& one = static_cast<ConfigOptionVectorBase&>(*out);
    one.set_at(&values, 0, std::min(i, values.size() - 1));
    one.resize(1);
    return out;
}

std::string label_of(PresetKind kind)
{
    return kind == PresetKind::Printer ? "printer" : kind == PresetKind::Process ? "process" : "filament";
}

// The user preset `name` of `kind` from `source` (filament `index`'s values for a
// filament): what it sets differently from the system preset it inherits. Null
// when it is that system preset unchanged, or when its parent is unknown (a problem).
std::optional<UserPreset> user_preset_from(PresetBundle& bundle, const std::string& vendor, PresetKind kind, const std::string& name,
                                           const std::string& inherits, const DynamicPrintConfig& source, std::size_t index,
                                           std::vector<std::string>& problems)
{
    Slic3r::PresetCollection& collection = collection_of(bundle, kind);
    const Preset* parent = inherits.empty() ? nullptr : collection.find_preset2(inherits, true);
    if (parent == nullptr && inherits.empty())
        parent = collection.find_preset(name, false);   // a system preset, as the project chose it
    if (parent == nullptr || !parent->is_system || parent->is_default) {
        problems.push_back("The " + label_of(kind) + " preset \"" + name + "\" is based on " +
                           (inherits.empty() ? std::string("no system preset") : "\"" + inherits + "\"") + ", which " + vendor +
                           "'s presets don't have.");
        return std::nullopt;
    }
    UserPreset preset{kind, name, parent->name, {}};
    for (const std::string& key : keys_of(kind)) {
        const ConfigOption* option = source.option(key);
        if (option == nullptr || is_bookkeeping(key))
            continue;
        const std::unique_ptr<ConfigOption> value = kind == PresetKind::Filament ? element_of(*option, index) : std::unique_ptr<ConfigOption>(option->clone());
        const ConfigOption* base = parent->config.option(key);
        if (base != nullptr && *base == *value)
            continue;
        preset.values[key] = value->serialize();
    }
    if (preset.name == parent->name) {
        if (preset.values.empty())
            return std::nullopt;   // the system preset itself: nothing to import
        preset.name += " - Imported";
    }
    return preset;
}

std::string text_value(const DynamicPrintConfig& config, const std::string& key, std::size_t i = 0)
{
    const ConfigOption* option = config.option(key);
    if (option == nullptr)
        return {};
    if (option->is_vector()) {
        const auto values = static_cast<const ConfigOptionVectorBase*>(option)->vserialize();
        return i < values.size() ? values[i] : std::string();
    }
    if (const auto* text = dynamic_cast<const Slic3r::ConfigOptionString*>(option))
        return text->value;
    return option->serialize();
}

} // namespace

PresetFileContents read_preset_file(PresetCatalog& catalog, const std::string& vendor, const std::string& path)
{
    DynamicPrintConfig config;
    std::map<std::string, std::string> header;
    std::string reason;
    Slic3r::ConfigSubstitutionContext substitutions(Slic3r::ForwardCompatibilitySubstitutionRule::EnableSilent);
    if (config.load_from_json(path, substitutions, true, header, reason) != 0)
        throw std::runtime_error(reason.empty() ? "This isn't a preset file" : "This isn't a preset file: " + reason);

    PresetCatalog::Impl& impl = catalog.impl();
    std::lock_guard<std::mutex> lock(impl.mutex);
    PresetBundle& bundle = impl.bundle_for(vendor);
    PresetFileContents out;
    auto add = [&](PresetKind kind, const std::string& name, const std::string& inherits, std::size_t index) {
        if (name.empty())
            return;
        for (const UserPreset& known : out.presets)
            if (known.kind == kind && known.name == name)
                return;   // a filament loaded twice
        if (auto preset = user_preset_from(bundle, vendor, kind, name, inherits, config, index, out.problems))
            out.presets.push_back(std::move(*preset));
    };

    if (const auto* groups = config.option<Slic3r::ConfigOptionStrings>("inherits_group")) {
        // A project's settings: process, filaments, printer, each with its parent in inherits_group.
        const std::vector<std::string>& inherits = groups->values;
        auto parent = [&](std::size_t i) { return i < inherits.size() ? inherits[i] : std::string(); };
        const auto* filaments = config.option<Slic3r::ConfigOptionStrings>("filament_settings_id");
        const std::size_t count = filaments ? filaments->values.size() : 0;
        add(PresetKind::Process, text_value(config, "print_settings_id"), parent(0), 0);
        for (std::size_t i = 0; i < count; ++i)
            add(PresetKind::Filament, filaments->values[i], parent(1 + i), i);
        add(PresetKind::Printer, text_value(config, "printer_settings_id"), parent(count + 1), 0);
        return out;
    }

    // One preset: its kind from its settings id, as the desktop's import tells them apart.
    for (PresetKind kind : {PresetKind::Printer, PresetKind::Process, PresetKind::Filament}) {
        if (!config.has(settings_id_key(kind)))
            continue;
        std::string name = header.count("name") ? header["name"] : std::string();
        if (name.empty())
            name = text_value(config, settings_id_key(kind));
        add(kind, name, text_value(config, "inherits"), 0);
        return out;
    }
    throw std::runtime_error("This file holds no printer, process or filament preset");
}

std::string user_preset_json(const UserPreset& preset)
{
    DynamicPrintConfig config;
    Slic3r::ConfigSubstitutionContext substitutions(Slic3r::ForwardCompatibilitySubstitutionRule::EnableSilent);
    for (const auto& [key, value] : preset.values) {
        if (config.def()->get(key) == nullptr)
            continue;
        try {
            config.set_deserialize(key, value, substitutions);
        } catch (const std::exception&) {
            // A value this version can't read stays out.
        }
    }
    config.set_key_value("inherits", new Slic3r::ConfigOptionString(preset.inherits));
    if (preset.kind == PresetKind::Filament)
        config.set_key_value("filament_settings_id", new Slic3r::ConfigOptionStrings({preset.name}));
    else
        config.set_key_value(settings_id_key(preset.kind), new Slic3r::ConfigOptionString(preset.name));
    std::ostringstream out;
    config.save_to_json(out, preset.name, "User", SLIC3R_VERSION);
    return out.str();
}

} // namespace orca::engine
