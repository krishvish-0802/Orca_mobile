// Command-line harness: slices one model with OrcaSlicer's defaults plus
// key=value overrides and prints the outcome as JSON. Used to test the engine
// on a device (adb push + adb shell) without the app, and by CI.
//
//   orca_slice_cli <model> <output.gcode> [--resources DIR] [key=value ...]
#include <cstdio>
#include <iostream>
#include <string>

#include "orca_engine/EngineEnvironment.hpp"
#include "orca_engine/EngineInfo.hpp"
#include "orca_engine/Json.hpp"
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
    std::cerr << "usage: orca_slice_cli <model> <output.gcode> [--resources DIR] [key=value ...]\n"
                 "       orca_slice_cli --version\n";
    return 2;
}

} // namespace

int main(int argc, char** argv)
{
    if (argc == 2 && std::string(argv[1]) == "--version") {
        std::cout << orca::engine::json::to_json(orca::engine::engine_info()) << '\n';
        return 0;
    }
    if (argc < 3)
        return usage();

    orca::engine::SliceRequest request;
    request.model_path = argv[1];
    request.output_gcode_path = argv[2];
    orca::engine::EnvironmentPaths paths;
    for (int i = 3; i < argc; ++i) {
        const std::string arg = argv[i];
        if (arg == "--resources" && i + 1 < argc) {
            paths.resources_dir = argv[++i];
            continue;
        }
        const auto eq = arg.find('=');
        if (eq == std::string::npos || eq == 0)
            return usage();
        request.config_overrides[arg.substr(0, eq)] = arg.substr(eq + 1);
    }

    orca::engine::initialize_environment(paths);
    StderrProgress progress;
    orca::engine::CancelToken cancel;
    const orca::engine::SliceOutcome outcome = orca::engine::run_slice(request, progress, cancel);
    std::cout << orca::engine::json::to_json(outcome) << '\n';
    return outcome.status == orca::engine::SliceOutcome::Status::Succeeded ? 0 : 1;
}
