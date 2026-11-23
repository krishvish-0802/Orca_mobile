// The desktop's Variable layer height (GLCanvas3D::LayersEditing) for one object.
#include "orca_engine/LayerHeights.hpp"

#include <algorithm>
#include <cmath>

#include "ConfigBuilder.hpp"
#include "MeshToolsCommon.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/Slicing.hpp"

namespace orca::engine {

namespace {

// LayersEditing::slicing_parameters_of, without shrinkage compensation.
Slic3r::SlicingParameters parameters_of(const Slic3r::ModelObject& object, const PlateConfig& config, PresetCatalog* catalog)
{
    const Slic3r::DynamicPrintConfig full = ConfigBuilder::resolve(config.presets, config.overrides, catalog);
    return Slic3r::PrintObject::slicing_parameters(full, object, float(object.max_z()), Slic3r::Vec3d::Ones());
}

// The profile the slice uses: the object's own when it fits, else from its ranges.
std::vector<double> current_profile(const Slic3r::ModelObject& object, const Slic3r::SlicingParameters& params)
{
    std::vector<double> profile;
    Slic3r::PrintObject::update_layer_height_profile(object, params, profile);
    return profile;
}

LayerProfile describe(const Slic3r::ModelObject& object, const Slic3r::SlicingParameters& params, std::vector<double> profile)
{
    LayerProfile out;
    out.layers = Slic3r::generate_object_layers(params, profile, false);
    out.profile = std::move(profile);
    out.layer_height = params.layer_height;
    out.min_layer_height = params.min_layer_height;
    out.max_layer_height = params.max_layer_height;
    out.object_height = params.object_print_z_height();
    out.custom = !object.layer_height_profile.empty();
    return out;
}

// Runs `change` on the object's current profile and keeps the result as its own.
template <typename Change>
LayerProfile change_profile(Project& project, std::size_t index, const PlateConfig& config, PresetCatalog* catalog, Change change)
{
    LayerProfile out;
    project.edit([&](Slic3r::Model& model) {
        Slic3r::ModelObject& object = detail::object_at(model, index);
        const Slic3r::SlicingParameters params = parameters_of(object, config, catalog);
        std::vector<double> profile = change(object, params, current_profile(object, params));
        if (profile.size() < 4)
            throw std::invalid_argument("The object is too thin for a layer height profile");
        object.layer_height_profile.set(profile);
        out = describe(object, params, std::move(profile));
    });
    return out;
}

} // namespace

LayerProfile layer_profile(const Project& project, std::size_t index, const PlateConfig& config, PresetCatalog* catalog)
{
    LayerProfile out;
    project.inspect([&](const Slic3r::Model& model) {
        const Slic3r::ModelObject& object = detail::object_at(model, index);
        const Slic3r::SlicingParameters params = parameters_of(object, config, catalog);
        out = describe(object, params, current_profile(object, params));
    });
    return out;
}

LayerProfile adaptive_layer_profile(Project& project, std::size_t object, const PlateConfig& config, PresetCatalog* catalog,
                                    double quality)
{
    return change_profile(project, object, config, catalog,
                          [&](const Slic3r::ModelObject& o, const Slic3r::SlicingParameters& p, const std::vector<double>&) {
                              return Slic3r::layer_height_profile_adaptive(p, o, float(std::clamp(quality, 0.0, 1.0)));
                          });
}

LayerProfile smooth_layer_profile(Project& project, std::size_t object, const PlateConfig& config, PresetCatalog* catalog,
                                  unsigned radius, bool keep_min)
{
    return change_profile(project, object, config, catalog,
                          [&](const Slic3r::ModelObject&, const Slic3r::SlicingParameters& p, const std::vector<double>& current) {
                              return Slic3r::smooth_height_profile(current, p,
                                                                   Slic3r::HeightProfileSmoothingParams(std::clamp(radius, 1u, 10u), keep_min));
                          });
}

LayerProfile brush_layer_profile(Project& project, std::size_t object, const PlateConfig& config, PresetCatalog* catalog,
                                 double z, double delta, double band_width, LayerBrush brush)
{
    return change_profile(project, object, config, catalog,
                          [&](const Slic3r::ModelObject& o, const Slic3r::SlicingParameters& p, std::vector<double> current) {
                              Slic3r::adjust_layer_height_profile(o, p, current, z, std::abs(delta), std::clamp(band_width, 0.5, 50.0),
                                                                  static_cast<Slic3r::LayerHeightEditActionType>(brush));
                              return current;
                          });
}

LayerProfile reset_layer_profile(Project& project, std::size_t index, const PlateConfig& config, PresetCatalog* catalog)
{
    LayerProfile out;
    project.edit([&](Slic3r::Model& model) {
        Slic3r::ModelObject& object = detail::object_at(model, index);
        object.layer_height_profile.clear();
        const Slic3r::SlicingParameters params = parameters_of(object, config, catalog);
        out = describe(object, params, current_profile(object, params));
    });
    return out;
}

} // namespace orca::engine
