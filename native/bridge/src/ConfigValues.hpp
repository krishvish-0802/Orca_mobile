#pragma once

#include <map>
#include <string>
#include <vector>

#include "libslic3r/PrintConfig.hpp"

namespace orca::engine {

// `keys` present in `config`, serialized as in profiles.
inline std::map<std::string, std::string> config_values(const Slic3r::DynamicPrintConfig& config,
                                                        const std::vector<std::string>& keys)
{
    std::map<std::string, std::string> values;
    for (const std::string& key : keys)
        if (const Slic3r::ConfigOption* option = config.option(key))
            values[key] = option->serialize();
    return values;
}

} // namespace orca::engine
