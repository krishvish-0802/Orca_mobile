#include "orca_engine/PresetFiles.hpp"

#include <algorithm>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <sstream>
#include <stdexcept>

#include "ConfigBuilder.hpp"
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

Slic3r::DynamicPrintConfig project_settings(PresetCatalog* catalog, const std::optional<PresetSelection>& presets,
                                            const std::map<std::string, std::string>& overrides)
{
    // As the settings stand, not as a slice forces them (the desktop saves the presets as edited).
    DynamicPrintConfig config = ConfigBuilder::resolve(presets, overrides, catalog, /*normalize=*/false);
    if (!presets || catalog == nullptr)
        return config;

    PresetCatalog::Impl& impl = catalog->impl();
    std::lock_guard<std::mutex> lock(impl.mutex);
    PresetBundle& bundle = impl.bundle_for(presets->vendor);
    const auto user_of = [&](PresetKind kind, const std::string& name) -> const UserPreset* {
        const auto it = impl.user_presets.find({kind, name});
        return it == impl.user_presets.end() ? nullptr : &it->second;
    };
    std::vector<std::string> inherits;
    std::vector<std::string> different;
    // One preset: the system preset it is (or is based on), and what the project's settings change from it.
    const auto add = [&](PresetKind kind, const std::string& name, std::size_t index) {
        const UserPreset* user = user_of(kind, name);
        inherits.push_back(user != nullptr ? user->inherits : std::string());
        const Preset* system = collection_of(bundle, kind).find_preset(user != nullptr ? user->inherits : name, false);
        std::vector<std::string> changed;
        if (system != nullptr) {
            for (const std::string& key : keys_of(kind)) {
                const ConfigOption* option = config.option(key);
                const ConfigOption* base = system->config.option(key);
                if (option == nullptr || base == nullptr || is_bookkeeping(key))
                    continue;
                const auto value = kind == PresetKind::Filament ? element_of(*option, index) : std::unique_ptr<ConfigOption>(option->clone());
                if (!(*base == *value))
                    changed.push_back(key);
            }
        }
        different.push_back(Slic3r::escape_strings_cstyle(changed));
    };
    add(PresetKind::Process, presets->process, 0);
    for (std::size_t i = 0; i < presets->filaments.size(); ++i)
        add(PresetKind::Filament, presets->filaments[i], i);
    add(PresetKind::Printer, presets->printer, 0);
    config.option<Slic3r::ConfigOptionStrings>("inherits_group", true)->values = inherits;
    config.option<Slic3r::ConfigOptionStrings>("different_settings_to_system", true)->values = different;
    return config;
}

ProjectPresets project_presets(PresetCatalog& catalog, const std::string& vendor, const DynamicPrintConfig& settings)
{
    ProjectPresets out;
    out.selection.vendor = vendor;
    const auto* groups = settings.option<Slic3r::ConfigOptionStrings>("inherits_group");
    const auto* differing = settings.option<Slic3r::ConfigOptionStrings>("different_settings_to_system");
    const auto* filament_names = settings.option<Slic3r::ConfigOptionStrings>("filament_settings_id");
    const std::size_t filaments = filament_names ? filament_names->values.size() : 0;
    const auto entry = [](const Slic3r::ConfigOptionStrings* list, std::size_t i) {
        return list != nullptr && i < list->values.size() ? list->values[i] : std::string();
    };

    // Which preset each of the project's is here, and which settings to compare
    // (those it says it changes; all of its kind where it falls back to its system preset).
    std::set<std::string> compare;
    {
        PresetCatalog::Impl& impl = catalog.impl();
        std::lock_guard<std::mutex> lock(impl.mutex);
        PresetBundle& bundle = impl.bundle_for(vendor);
        const auto pick = [&](PresetKind kind, const std::string& name, std::size_t group) -> std::string {
            if (name.empty())
                return {};
            std::vector<std::string> listed;
            Slic3r::unescape_strings_cstyle(entry(differing, group), listed);
            compare.insert(listed.begin(), listed.end());
            if (impl.user_presets.count({kind, name}) > 0)
                return name;
            const Slic3r::PresetCollection& collection = collection_of(bundle, kind);
            if (const Preset* system = collection.find_preset(name, false); system != nullptr && system->is_system && !system->is_default)
                return system->name;
            const std::string inherits = entry(groups, group);
            const Preset* parent = inherits.empty() ? nullptr : collection_of(bundle, kind).find_preset2(inherits, true);
            if (parent == nullptr || !parent->is_system || parent->is_default) {
                out.problems.push_back("The project's " + label_of(kind) + " preset \"" + name + "\" isn't one of " + vendor + "'s here.");
                return {};
            }
            // Its own settings come over the system preset it is based on.
            for (const std::string& key : keys_of(kind))
                compare.insert(key);
            return parent->name;
        };
        out.selection.process = pick(PresetKind::Process, text_value(settings, "print_settings_id"), 0);
        for (std::size_t i = 0; i < filaments; ++i)
            out.selection.filaments.push_back(pick(PresetKind::Filament, filament_names->values[i], 1 + i));
        out.selection.printer = pick(PresetKind::Printer, text_value(settings, "printer_settings_id"), filaments + 1);
    }
    if (const auto* colours = settings.option<Slic3r::ConfigOptionStrings>("filament_colour"))
        out.selection.filament_colours = colours->values;
    const bool complete = !out.selection.printer.empty() && !out.selection.process.empty() && !out.selection.filaments.empty() &&
        std::none_of(out.selection.filaments.begin(), out.selection.filaments.end(), [](const std::string& f) { return f.empty(); });
    if (!complete)
        return out;

    // What the project sets differently from those presets.
    // Built as a save builds it, so only what the project really changes differs.
    const DynamicPrintConfig composed = ConfigBuilder::resolve(out.selection, {}, &catalog, /*normalize=*/false);
    for (const std::string& key : compare) {
        if (is_bookkeeping(key) || key == "filament_colour")
            continue;
        const ConfigOption* mine = settings.option(key);
        const ConfigOption* theirs = composed.option(key);
        if (mine != nullptr && theirs != nullptr && !(*mine == *theirs) && mine->type() == theirs->type())
            out.changes[key] = mine->serialize();
    }
    return out;
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
