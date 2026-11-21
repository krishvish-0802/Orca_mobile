// Command-line harness for the engine, used to test it on a device
// (adb push + adb shell) without the app, and by CI. Prints JSON results.
//
//   orca_slice_cli --version
//   orca_slice_cli --preview <file.gcode>      (what the preview screen reads)
//   orca_slice_cli --profiles DIR --list-vendors
//   orca_slice_cli --profiles DIR --list-printers VENDOR
//   orca_slice_cli --profiles DIR --compatible VENDOR PRINTER
//   orca_slice_cli <model> <output.gcode> [--profiles DIR --vendor V --printer P
//                  --process Q --filament F [--filament F2 ...]] [key=value ...]
//
// Without presets a slice uses OrcaSlicer's defaults plus the key=value overrides.
#include <cstdio>
#include <exception>
#include <iostream>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "orca_engine/EngineEnvironment.hpp"
#include "orca_engine/EngineInfo.hpp"
#include "orca_engine/GCodePreview.hpp"
#include "orca_engine/Json.hpp"
#include "orca_engine/PresetCatalog.hpp"
#include "orca_engine/SliceJob.hpp"

namespace {

class StderrProgress final : public orca::engine::ProgressSink {
public:
    void on_progress(int percent, const std::string& message) override
    {
        std::fprintf(stderr, "[%3d%%] %s\n", percent, message.c_str());
    }
};

int usage()
{
    std::cerr << "usage: orca_slice_cli --version\n"
                 "       orca_slice_cli --preview FILE.gcode\n"
                 "       orca_slice_cli --profiles DIR --list-vendors\n"
                 "       orca_slice_cli --profiles DIR --list-printers VENDOR\n"
                 "       orca_slice_cli --profiles DIR --compatible VENDOR PRINTER\n"
                 "       orca_slice_cli <model> <output.gcode> [--profiles DIR --vendor V --printer P\n"
                 "                      --process Q --filament F ...] [key=value ...]\n";
    return 2;
}

struct Arguments {
    std::vector<std::string> positional;
    std::string profiles_dir;
    std::string list;                      // "vendors", "printers" or "compatible"
    orca::engine::PresetSelection presets;
    std::map<std::string, std::string> overrides;
};

bool parse(int argc, char** argv, Arguments& args)
{
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        auto value = [&](std::string& out) {
            if (i + 1 >= argc)
                return false;
            out = argv[++i];
            return true;
        };
        if (arg == "--profiles") { if (!value(args.profiles_dir)) return false; }
        else if (arg == "--vendor") { if (!value(args.presets.vendor)) return false; }
        else if (arg == "--printer") { if (!value(args.presets.printer)) return false; }
        else if (arg == "--process") { if (!value(args.presets.process)) return false; }
        else if (arg == "--filament") {
            std::string name;
            if (!value(name)) return false;
            args.presets.filaments.push_back(name);
        }
        else if (arg == "--list-vendors") { args.list = "vendors"; }
        else if (arg == "--list-printers") { args.list = "printers"; }
        else if (arg == "--compatible") { args.list = "compatible"; }
        else if (arg.rfind("--", 0) == 0) { return false; }
        else if (const auto eq = arg.find('='); eq != std::string::npos && eq > 0) {
            args.overrides[arg.substr(0, eq)] = arg.substr(eq + 1);
        } else {
            args.positional.push_back(arg);
        }
    }
    return true;
}

int run_list(const Arguments& args, orca::engine::PresetCatalog& catalog)
{
    namespace json = orca::engine::json;
    if (args.list == "vendors") {
        std::cout << json::to_json(catalog.vendors()) << '\n';
    } else if (args.list == "printers" && args.positional.size() == 1) {
        std::cout << json::to_json(catalog.printers(args.positional[0])) << '\n';
    } else if (args.list == "compatible" && args.positional.size() == 2) {
        std::cout << json::to_json(catalog.compatible_presets(args.positional[0], args.positional[1])) << '\n';
    } else {
        return usage();
    }
    return 0;
}

} // namespace

int main(int argc, char** argv)
{
    if (argc == 2 && std::string(argv[1]) == "--version") {
        std::cout << orca::engine::json::to_json(orca::engine::engine_info()) << '\n';
        return 0;
    }
    if (argc == 3 && std::string(argv[1]) == "--preview") {
        try {
            const auto preview = orca::engine::GCodePreview::load(argv[2]);
            std::cout << orca::engine::json::to_json(preview->summary()) << '\n';
            return 0;
        } catch (const std::exception& e) {
            std::cout << orca::engine::json::error_json(e.what()) << '\n';
            return 1;
        }
    }
    Arguments args;
    if (!parse(argc, argv, args))
        return usage();

    orca::engine::EnvironmentPaths paths;
    std::unique_ptr<orca::engine::PresetCatalog> catalog;
    if (!args.profiles_dir.empty()) {
        // libslic3r looks for <resources>/profiles; the catalog reads the directory itself.
        const auto slash = args.profiles_dir.find_last_of('/');
        paths.resources_dir = slash == std::string::npos ? "." : args.profiles_dir.substr(0, slash);
        catalog = std::make_unique<orca::engine::PresetCatalog>(args.profiles_dir);
    }
    orca::engine::initialize_environment(paths);

    try {
        if (!args.list.empty()) {
            if (!catalog)
                return usage();
            return run_list(args, *catalog);
        }
        if (args.positional.size() != 2)
            return usage();

        orca::engine::SliceRequest request;
        request.model_path = args.positional[0];
        request.output_gcode_path = args.positional[1];
        request.config_overrides = args.overrides;
        if (!args.presets.printer.empty())
            request.presets = args.presets;

        StderrProgress progress;
        orca::engine::CancelToken cancel;
        const orca::engine::SliceOutcome outcome = orca::engine::run_slice(request, progress, cancel, catalog.get());
        std::cout << orca::engine::json::to_json(outcome) << '\n';
        return outcome.status == orca::engine::SliceOutcome::Status::Succeeded ? 0 : 1;
    } catch (const std::exception& e) {
        std::cout << orca::engine::json::error_json(e.what()) << '\n';
        return 1;
    }
}
