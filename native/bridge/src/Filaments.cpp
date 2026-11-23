#include "Filaments.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>

#include "libslic3r/FlushVolCalc.hpp"

namespace orca::engine {

namespace {

struct Rgba {
    unsigned char r = 255, g = 255, b = 255, a = 255;
};

// "#RRGGBB" or "#RRGGBBAA"; anything else reads as opaque white, as wxColour
// leaves an unparsable colour.
Rgba parse_colour(const std::string& text)
{
    Rgba c;
    if (text.size() != 7 && text.size() != 9)
        return c;
    if (text[0] != '#')
        return c;
    unsigned int r = 0, g = 0, b = 0, a = 255;
    if (std::sscanf(text.c_str() + 1, "%2x%2x%2x", &r, &g, &b) != 3)
        return Rgba{};
    if (text.size() == 9 && std::sscanf(text.c_str() + 7, "%2x", &a) != 1)
        return Rgba{};
    c.r = static_cast<unsigned char>(r);
    c.g = static_cast<unsigned char>(g);
    c.b = static_cast<unsigned char>(b);
    c.a = static_cast<unsigned char>(a);
    return c;
}

bool is_colour(const std::string& text)
{
    if ((text.size() != 7 && text.size() != 9) || text[0] != '#')
        return false;
    return std::all_of(text.begin() + 1, text.end(), [](char ch) { return std::isxdigit(static_cast<unsigned char>(ch)) != 0; });
}

// get_min_flush_volumes (Plater.cpp): the nozzle's volume, less what a long
// retraction on cutting already pulls out.
std::vector<int> min_flush_volumes(const Slic3r::DynamicPrintConfig& config, std::size_t nozzle, std::size_t filaments)
{
    const auto* nozzle_volume = config.option<Slic3r::ConfigOptionFloatsNullable>("nozzle_volume");
    const int volume = nozzle_volume && !nozzle_volume->values.empty() ? int(nozzle_volume->get_at(nozzle)) : 0;

    const auto* level_opt = config.option<Slic3r::ConfigOptionInt>("enable_long_retraction_when_cut");
    const int machine_level = level_opt ? level_opt->value : 0;
    const auto* machine_opt = config.option<Slic3r::ConfigOptionBools>("long_retractions_when_cut");
    const bool machine_on = machine_opt && nozzle < machine_opt->values.size() && machine_opt->values[nozzle] == 1;
    const auto* printer_cut = config.option<Slic3r::ConfigOptionFloats>("retraction_distances_when_cut");
    const auto* filament_cut = config.option<Slic3r::ConfigOptionFloats>("filament_retraction_distances_when_cut");
    const auto* filament_on = config.option<Slic3r::ConfigOptionBools>("filament_long_retractions_when_cut");

    std::vector<int> out;
    for (std::size_t i = 0; i < filaments; ++i) {
        double retract = machine_level && machine_on && printer_cut && !printer_cut->values.empty() ? printer_cut->get_at(nozzle) : 0;
        const unsigned char activated = filament_on && !filament_on->values.empty() ? filament_on->get_at(i) : 0;
        if (activated == 0) {
            retract = 0;
        } else if (activated == 1 && machine_level == Slic3r::LongRectrationLevel::EnableFilament) {
            const double own = filament_cut && !filament_cut->values.empty() ? filament_cut->get_at(i) : NAN;
            if (!std::isnan(own))
                retract = own;
            else if (printer_cut && !printer_cut->values.empty())
                retract = printer_cut->get_at(nozzle);
        }
        out.push_back(int(volume - M_PI * 1.75 * 1.75 / 4 * retract));
    }
    return out;
}

} // namespace

std::vector<double> flush_volumes(const Slic3r::DynamicPrintConfig& config)
{
    const auto* colour_opt = config.option<Slic3r::ConfigOptionStrings>("filament_colour");
    const std::size_t n = colour_opt ? colour_opt->values.size() : 0;
    const auto* nozzle_opt = config.option<Slic3r::ConfigOptionFloats>("nozzle_diameter");
    const std::size_t nozzles = std::max<std::size_t>(1, nozzle_opt ? nozzle_opt->values.size() : 1);
    const auto* support_opt = config.option<Slic3r::ConfigOptionBools>("filament_is_support");
    const auto* dataset_opt = config.option<Slic3r::ConfigOptionIntsNullable>("nozzle_flush_dataset");
    const auto is_support = [&](std::size_t i) {
        return support_opt && i < support_opt->values.size() && support_opt->values[i] != 0;
    };

    std::vector<Rgba> colours;
    for (std::size_t i = 0; i < n; ++i)
        colours.push_back(parse_colour(colour_opt->values[i]));

    std::vector<double> matrix;
    matrix.reserve(nozzles * n * n);
    for (std::size_t nozzle = 0; nozzle < nozzles; ++nozzle) {
        int dataset = 0;
        if (dataset_opt && nozzle < dataset_opt->values.size() && !dataset_opt->is_nil(nozzle))
            dataset = dataset_opt->values[nozzle];
        const std::vector<int> minimum = min_flush_volumes(config, nozzle, n);
        for (std::size_t from = 0; from < n; ++from) {
            Slic3r::FlushVolCalculator calculator(minimum[from], Slic3r::g_max_flush_volume, dataset);
            for (std::size_t to = 0; to < n; ++to) {
                int volume = 0;
                if (from != to) {
                    if (is_support(to)) {
                        volume = Slic3r::g_flush_volume_to_support;
                    } else {
                        const Rgba& a = colours[from];
                        const Rgba& b = colours[to];
                        volume = calculator.calc_flush_vol(a.a, a.r, a.g, a.b, b.a, b.r, b.g, b.b);
                        if (is_support(from))
                            volume = std::max(volume, Slic3r::g_min_flush_volume_from_support);
                    }
                }
                matrix.push_back(volume);
            }
        }
    }
    return matrix;
}

void apply_filaments(Slic3r::DynamicPrintConfig& config, const std::vector<std::string>& colours)
{
    auto* colour_opt = config.option<Slic3r::ConfigOptionStrings>("filament_colour", true);
    const std::size_t n = config.option<Slic3r::ConfigOptionFloats>("filament_diameter", true)->values.size();
    if (colour_opt->values.size() < n)
        colour_opt->values.resize(n, colour_opt->values.empty() ? "#F2754E" : colour_opt->values.back());
    for (std::size_t i = 0; i < n && i < colours.size(); ++i)
        if (is_colour(colours[i]))
            colour_opt->values[i] = colours[i];

    // Every filament on the first nozzle unless the profile maps them.
    auto& filament_map = config.option<Slic3r::ConfigOptionInts>("filament_map", true)->values;
    if (filament_map.size() != n)
        filament_map.resize(n, 1);

    if (n > 1)
        config.option<Slic3r::ConfigOptionFloats>("flush_volumes_matrix", true)->values = flush_volumes(config);
}

} // namespace orca::engine
