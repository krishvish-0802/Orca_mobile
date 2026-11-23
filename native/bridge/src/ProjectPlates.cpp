// A project's build plates (the desktop's PartPlateList without its GL side).
#include "orca_engine/Project.hpp"
#include "ProjectImpl.hpp"

#include <algorithm>
#include <stdexcept>

#include "libslic3r/Config.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/PrintConfig.hpp"

namespace orca::engine {

namespace {

class Membership final : public PlateMembership {
public:
    explicit Membership(Project::Impl& impl) : m_impl(impl) {}
    std::size_t current() const override { return m_impl.current_plate; }
    std::size_t count() const override { return m_impl.plates.size(); }
    std::size_t plate_of(const Slic3r::ModelInstance& instance) override { return m_impl.plate_of(instance); }
    void assign(const Slic3r::ModelInstance& instance, std::size_t plate) override
    {
        m_impl.check_plate(plate);
        m_impl.plate_by_instance[instance.id().id] = plate;
    }
    std::size_t add_plate() override
    {
        m_impl.plates.emplace_back();
        return m_impl.plates.size() - 1;
    }
    std::map<std::string, std::string> settings() const override { return m_impl.plates[m_impl.current_plate].settings; }

private:
    Project::Impl& m_impl;
};

void check_setting(const std::string& key, const std::string& value)
{
    const auto keys = plate_setting_keys();
    if (std::find(keys.begin(), keys.end(), key) == keys.end())
        throw std::invalid_argument("A plate does not take this setting: " + key);
    Slic3r::DynamicPrintConfig probe;
    Slic3r::ConfigSubstitutionContext context(Slic3r::ForwardCompatibilitySubstitutionRule::Disable);
    probe.set_deserialize(key, value, context);   // throws for a value that does not parse
}

} // namespace

std::vector<std::string> plate_setting_keys()
{
    // PlateSettingsDialog: plate type, print sequence, spiral vase.
    return {"curr_bed_type", "print_sequence", "spiral_mode"};
}

std::vector<PlateSummary> Project::plates() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    std::vector<PlateSummary> out;
    for (const Impl::Plate& plate : m_impl->plates)
        out.push_back({plate.name, plate.settings, 0});
    for (const Slic3r::ModelObject* object : m_impl->model.objects)
        for (const Slic3r::ModelInstance* instance : object->instances)
            ++out[m_impl->plate_of(*instance)].instance_count;
    return out;
}

std::size_t Project::current_plate() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    return m_impl->current_plate;
}

void Project::select_plate(std::size_t plate)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->check_plate(plate);
    // Anything not yet seen belongs to the plate it was made on, not the next one.
    for (const Slic3r::ModelObject* object : m_impl->model.objects)
        for (const Slic3r::ModelInstance* instance : object->instances)
            m_impl->plate_of(*instance);
    m_impl->current_plate = plate;
}

std::size_t Project::add_plate()
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->plates.emplace_back();
    return m_impl->plates.size() - 1;
}

void Project::remove_plate(std::size_t plate)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->check_plate(plate);
    if (m_impl->plates.size() == 1)
        throw std::invalid_argument("A project needs at least one plate");
    Slic3r::Model& model = m_impl->model;
    for (std::size_t o = model.objects.size(); o-- > 0;) {
        Slic3r::ModelObject* object = model.objects[o];
        for (std::size_t i = object->instances.size(); i-- > 0;)
            if (m_impl->plate_of(*object->instances[i]) == plate) {
                if (object->instances.size() == 1) {
                    model.delete_object(o);
                    break;
                }
                object->delete_instance(i);
            }
    }
    for (auto it = m_impl->plate_by_instance.begin(); it != m_impl->plate_by_instance.end();) {
        if (it->second == plate) {
            it = m_impl->plate_by_instance.erase(it);
            continue;
        }
        if (it->second > plate)
            --it->second;
        ++it;
    }
    m_impl->plates.erase(m_impl->plates.begin() + std::ptrdiff_t(plate));
    if (m_impl->current_plate > plate || m_impl->current_plate >= m_impl->plates.size())
        m_impl->current_plate = m_impl->current_plate == 0 ? 0 : m_impl->current_plate - 1;
}

void Project::rename_plate(std::size_t plate, const std::string& name)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->check_plate(plate);
    m_impl->plates[plate].name = name;
}

void Project::set_plate_setting(std::size_t plate, const std::string& key, const std::string& value)
{
    check_setting(key, value);
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->check_plate(plate);
    m_impl->plates[plate].settings[key] = value;
}

void Project::reset_plate_setting(std::size_t plate, const std::string& key)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->check_plate(plate);
    m_impl->plates[plate].settings.erase(key);
}

std::size_t Project::plate_of(const InstanceRef& ref) const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    return m_impl->plate_of(m_impl->instance(ref));
}

void Project::move_to_plate(const InstanceRef& ref, std::size_t plate)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->check_plate(plate);
    m_impl->plate_by_instance[m_impl->instance(ref).id().id] = plate;
}

std::vector<InstanceRef> Project::plate_instances() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    std::vector<InstanceRef> refs;
    for (std::size_t o = 0; o < m_impl->model.objects.size(); ++o)
        for (std::size_t i = 0; i < m_impl->model.objects[o]->instances.size(); ++i)
            if (m_impl->plate_of(*m_impl->model.objects[o]->instances[i]) == m_impl->current_plate)
                refs.push_back({o, i});
    return refs;
}

std::map<std::string, std::string> Project::plate_settings() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    return m_impl->plates[m_impl->current_plate].settings;
}

Slic3r::Model Project::plate_model_copy() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    // Which instances stay, read on the original (the copy's ids differ).
    std::vector<std::vector<bool>> keep;
    for (const Slic3r::ModelObject* object : m_impl->model.objects) {
        keep.emplace_back();
        for (const Slic3r::ModelInstance* instance : object->instances)
            keep.back().push_back(m_impl->plate_of(*instance) == m_impl->current_plate);
    }
    Slic3r::Model copy = m_impl->model;
    for (std::size_t o = copy.objects.size(); o-- > 0;) {
        Slic3r::ModelObject* object = copy.objects[o];
        for (std::size_t i = object->instances.size(); i-- > 0;)
            if (!keep[o][i])
                object->delete_instance(i);
        if (object->instances.empty())
            copy.delete_object(o);
    }
    return copy;
}

void Project::edit_plates(const std::function<void(Slic3r::Model&, PlateMembership&)>& change)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    Membership membership(*m_impl);
    change(m_impl->model, membership);
}

} // namespace orca::engine
