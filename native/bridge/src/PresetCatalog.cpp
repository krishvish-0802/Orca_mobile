#include "orca_engine/PresetCatalog.hpp"

#include <algorithm>
#include <filesystem>
#include <stdexcept>

#include "PresetCatalogImpl.hpp"

namespace orca::engine {

namespace fs = std::filesystem;
using Slic3r::Preset;
using Slic3r::PresetBundle;

namespace {

const Preset& require_preset(const Slic3r::PresetCollection& collection, const std::string& name, const char* kind)
{
    const Preset* preset = collection.find_preset(name, false);
    if (preset == nullptr || preset->is_default)
        throw std::runtime_error(std::string("Unknown ") + kind + " preset: " + name);
    return *preset;
}

bool is_listed(const Preset& preset)
{
    return preset.is_system && preset.is_visible && !preset.is_default;
}

} // namespace

// --- Impl ----------------------------------------------------------------

PresetBundle& PresetCatalog::Impl::bundle_for(const std::string& vendor)
{
    if (auto it = bundles.find(vendor); it != bundles.end())
        return *it->second;

    const fs::path dir(profiles_dir);
    if (!fs::is_regular_file(dir / (vendor + ".json")))
        throw std::runtime_error("Unknown vendor: " + vendor);

    std::vector<PresetBundle::VendorSource> sources{{PresetBundle::ORCA_FILAMENT_LIBRARY, dir.string()}};
    if (vendor != PresetBundle::ORCA_FILAMENT_LIBRARY)
        sources.push_back({vendor, dir.string()});

    auto bundle = std::make_unique<PresetBundle>();
    const auto [substitutions, errors] =
        bundle->load_vendors(sources, Slic3r::ForwardCompatibilitySubstitutionRule::EnableSilent, true);
    if (!errors.empty() && bundle->printers.size() <= bundle->printers.num_default_presets())
        throw std::runtime_error("Could not load vendor " + vendor + ": " + errors);

    return *bundles.emplace(vendor, std::move(bundle)).first->second;
}

Slic3r::DynamicPrintConfig PresetCatalog::Impl::compose(const PresetSelection& selection)
{
    std::lock_guard<std::mutex> lock(mutex);
    PresetBundle& bundle = bundle_for(selection.vendor);
    if (selection.filaments.empty())
        throw std::runtime_error("A slice needs at least one filament preset");

    // construct_full_config takes mutable presets; work on copies so the
    // catalog's bundle stays untouched.
    Preset printer = require_preset(bundle.printers, selection.printer, "printer");
    Preset process = require_preset(bundle.prints, selection.process, "process");
    std::vector<Preset> filaments;
    filaments.reserve(selection.filaments.size());
    for (const std::string& name : selection.filaments)
        filaments.push_back(require_preset(bundle.filaments, name, "filament"));

    const Slic3r::DynamicPrintConfig project_config;
    return PresetBundle::construct_full_config(printer, process, project_config, filaments, true, std::nullopt);
}

// --- PresetCatalog -------------------------------------------------------

PresetCatalog::PresetCatalog(std::string profiles_dir) : m_impl(std::make_unique<Impl>(std::move(profiles_dir))) {}

PresetCatalog::~PresetCatalog() = default;

const std::string& PresetCatalog::profiles_dir() const { return m_impl->profiles_dir; }

PresetCatalog::Impl& PresetCatalog::impl() { return *m_impl; }

std::vector<std::string> PresetCatalog::vendors() const
{
    std::vector<std::string> result;
    std::error_code ec;
    for (const auto& entry : fs::directory_iterator(m_impl->profiles_dir, ec)) {
        if (!entry.is_regular_file() || entry.path().extension() != ".json")
            continue;
        const std::string id = entry.path().stem().string();
        // A vendor bundle is <id>.json next to its <id>/ tree; other JSON files
        // in the directory (blacklist.json) are not vendors.
        if (id != PresetBundle::ORCA_FILAMENT_LIBRARY && fs::is_directory(entry.path().parent_path() / id))
            result.push_back(id);
    }
    std::sort(result.begin(), result.end());
    return result;
}

std::vector<std::string> PresetCatalog::printers(const std::string& vendor)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    const PresetBundle& bundle = m_impl->bundle_for(vendor);
    std::vector<std::string> result;
    for (const Preset& preset : bundle.printers)
        if (is_listed(preset))
            result.push_back(preset.name);
    std::sort(result.begin(), result.end());
    return result;
}

CompatiblePresets PresetCatalog::compatible_presets(const std::string& vendor, const std::string& printer)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    PresetBundle& bundle = m_impl->bundle_for(vendor);
    require_preset(bundle.printers, printer, "printer");
    // Same compatibility evaluation as the desktop tabs: select the printer, then
    // let the bundle flag every process and filament against it.
    bundle.printers.select_preset_by_name(printer, true);
    bundle.update_compatible(Slic3r::PresetSelectCompatibleType::Never);

    CompatiblePresets result;
    for (const Preset& preset : bundle.prints)
        if (is_listed(preset) && preset.is_compatible)
            result.processes.push_back(preset.name);
    for (const Preset& preset : bundle.filaments)
        if (is_listed(preset) && preset.is_compatible)
            result.filaments.push_back(preset.name);

    const Slic3r::DynamicPrintConfig& printer_config = bundle.printers.get_edited_preset().config;
    auto contains = [](const std::vector<std::string>& names, const std::string& name) {
        return std::find(names.begin(), names.end(), name) != names.end();
    };
    if (const auto* opt = printer_config.option<Slic3r::ConfigOptionString>("default_print_profile");
        opt != nullptr && contains(result.processes, opt->value))
        result.default_process = opt->value;
    if (const auto* opt = printer_config.option<Slic3r::ConfigOptionStrings>("default_filament_profile");
        opt != nullptr) {
        for (const std::string& name : opt->values)
            if (contains(result.filaments, name)) {
                result.default_filament = name;
                break;
            }
    }
    if (result.default_process.empty() && !result.processes.empty())
        result.default_process = result.processes.front();
    if (result.default_filament.empty() && !result.filaments.empty())
        result.default_filament = result.filaments.front();
    return result;
}

} // namespace orca::engine
