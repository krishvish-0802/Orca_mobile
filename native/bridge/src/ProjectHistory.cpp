// Undo and redo for a whole project: the desktop keeps an UndoRedo stack of the
// model and plates (src/slic3r/Utils/UndoRedo.cpp); here each step is a copy of
// the model, which shares its meshes with the project, and of the plates.
#include "orca_engine/Project.hpp"
#include "ProjectImpl.hpp"

namespace orca::engine {

namespace {

Project::Impl::State capture(const Project::Impl& impl)
{
    Project::Impl::State state;
    state.model = impl.model;   // a copy keeps the objects' ids, which the plates map
    state.plates = impl.plates;
    state.current_plate = impl.current_plate;
    state.plate_by_instance = impl.plate_by_instance;
    return state;
}

void restore(Project::Impl& impl, const Project::Impl::State& state)
{
    impl.model = state.model;
    impl.plates = state.plates;
    impl.current_plate = state.current_plate;
    impl.plate_by_instance = state.plate_by_instance;
    for (Slic3r::ModelObject* object : impl.model.objects)
        object->invalidate_bounding_box();
}

} // namespace

void Project::record_history()
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    auto& history = m_impl->history;
    if (!history.empty())
        history.erase(history.begin() + std::ptrdiff_t(m_impl->at) + 1, history.end());
    history.push_back(capture(*m_impl));
    if (history.size() > kHistorySteps)
        history.erase(history.begin(), history.begin() + std::ptrdiff_t(history.size() - kHistorySteps));
    m_impl->at = history.size() - 1;
}

bool Project::undo()
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    if (m_impl->history.empty() || m_impl->at == 0)
        return false;
    --m_impl->at;
    restore(*m_impl, m_impl->history[m_impl->at]);
    return true;
}

bool Project::redo()
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    if (m_impl->at + 1 >= m_impl->history.size())
        return false;
    ++m_impl->at;
    restore(*m_impl, m_impl->history[m_impl->at]);
    return true;
}

bool Project::can_undo() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    return !m_impl->history.empty() && m_impl->at > 0;
}

bool Project::can_redo() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    return m_impl->at + 1 < m_impl->history.size();
}

} // namespace orca::engine
