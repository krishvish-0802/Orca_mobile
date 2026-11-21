#include "orca_engine/ConfigOptions.hpp"

#include <cfloat>

#include "ConfigBuilder.hpp"
#include "ConfigValues.hpp"
#include "libslic3r/PrintConfig.hpp"

namespace orca::engine {

namespace {

std::string type_name(Slic3r::ConfigOptionType type)
{
    using namespace Slic3r;
    switch (type) {
    case coFloat: return "float";
    case coFloats: return "floats";
    case coInt: return "int";
    case coInts: return "ints";
    case coString: return "string";
    case coStrings: return "strings";
    case coPercent: return "percent";
    case coPercents: return "percents";
    case coFloatOrPercent: return "floatOrPercent";
    case coFloatsOrPercents: return "floatsOrPercents";
    case coPoint: return "point";
    case coPoints: return "points";
    case coPoint3: return "point3";
    case coBool: return "bool";
    case coBools: return "bools";
    case coEnum: return "enum";
    case coEnums: return "enums";
    default: return "other";
    }
}

std::string mode_name(Slic3r::ConfigOptionMode mode)
{
    switch (mode) {
    case Slic3r::comSimple: return "simple";
    case Slic3r::comAdvanced: return "advanced";
    case Slic3r::comExpert: return "expert";
    default: return "develop";
    }
}

} // namespace

std::vector<OptionInfo> describe_options(const std::vector<std::string>& keys)
{
    std::vector<OptionInfo> result;
    for (const std::string& key : keys) {
        const Slic3r::ConfigOptionDef* def = Slic3r::print_config_def.get(key);
        if (def == nullptr)
            continue;
        OptionInfo info;
        info.key = key;
        info.type = type_name(def->type);
        info.label = def->label;
        info.full_label = def->full_label.empty() ? def->label : def->full_label;
        info.category = def->category;
        info.tooltip = def->tooltip;
        info.unit = def->sidetext;
        info.mode = mode_name(def->mode);
        if (def->default_value)
            info.default_value = def->default_value->serialize();
        if (def->min > -FLT_MAX)
            info.min = def->min;
        if (def->max < FLT_MAX)
            info.max = def->max;
        info.enum_values = def->enum_values;
        info.enum_labels = def->enum_labels;
        info.nullable = def->nullable;
        result.push_back(std::move(info));
    }
    return result;
}

std::map<std::string, std::string> default_values(const std::vector<std::string>& keys)
{
    return config_values(ConfigBuilder::defaults(), keys);
}

} // namespace orca::engine
