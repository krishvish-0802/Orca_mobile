// Project::save: the project as the desktop's project file (bbs_3mf), which
// OrcaSlicer on a computer opens with its plates and settings.
#include <map>
#include <mutex>
#include <set>
#include <stdexcept>
#include <string>
#include <utility>

#include "ProjectImpl.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"
#include "orca_engine/Project.hpp"

namespace orca::engine {

void Project::save(const std::string& path, const Slic3r::DynamicPrintConfig& config) const
{
    Slic3r::Model model;
    std::vector<Impl::Plate> plates;
    std::vector<std::set<std::pair<int, int>>> on_plate;
    {
        std::lock_guard<std::mutex> lock(m_impl->mutex);
        model = m_impl->model;
        plates = m_impl->plates;
        on_plate.resize(plates.size());
        for (std::size_t o = 0; o < m_impl->model.objects.size(); ++o) {
            const Slic3r::ModelObject* object = m_impl->model.objects[o];
            for (std::size_t n = 0; n < object->instances.size(); ++n)
                on_plate[m_impl->plate_of(*object->instances[n])].emplace(int(o), int(n));
        }
    }

    // The desktop has its plates apart: each instance goes to its plate's place.
    if (plates.size() > 1) {
        const auto grid = desktop_plate_grid(int(plates.size()), config);
        if (!grid)
            throw std::runtime_error("The printer's bed is unknown, so the plates can't be laid out");
        for (std::size_t p = 0; p < plates.size(); ++p) {
            const Slic3r::Vec2d origin = grid->origin(int(p));
            for (const auto& [o, n] : on_plate[p]) {
                Slic3r::ModelInstance* instance = model.objects[o]->instances[n];
                instance->set_offset(instance->get_offset() + Slic3r::Vec3d(origin.x(), origin.y(), 0));
            }
        }
    }

    Slic3r::PlateDataPtrs plate_data;
    for (std::size_t p = 0; p < plates.size(); ++p) {
        auto* data = new Slic3r::PlateData(int(p), on_plate[p], false);
        data->plate_name = plates[p].name;
        Slic3r::ConfigSubstitutionContext substitutions(Slic3r::ForwardCompatibilitySubstitutionRule::EnableSilent);
        for (const auto& [key, value] : plates[p].settings) {
            try {
                data->config.set_deserialize(key, value, substitutions);
            } catch (const std::exception&) {
                // A plate setting this version can't read stays out.
            }
        }
        plate_data.push_back(data);
    }

    Slic3r::DynamicPrintConfig settings = config;
    Slic3r::StoreParams params;
    params.path = path;
    params.model = &model;
    params.plate_data_list = plate_data;
    params.export_plate_idx = -1;
    params.config = &settings;
    // As the desktop's Save project, without its backup bookkeeping.
    params.strategy = Slic3r::SaveStrategy::SplitModel | Slic3r::SaveStrategy::ShareMesh | Slic3r::SaveStrategy::Silence;
    const bool saved = Slic3r::store_bbs_3mf(params);
    Slic3r::release_PlateData_list(plate_data);
    if (!saved)
        throw std::runtime_error("The project could not be written to " + path);
}

} // namespace orca::engine
