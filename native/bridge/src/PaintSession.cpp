// Painting, as the desktop's painting gizmos (src/slic3r/GUI/Gizmos/
// GLGizmoPainterBase.cpp and its FDM supports, seam, fuzzy skin and MMU
// subclasses) drive libslic3r's TriangleSelector: one selector per part,
// loaded from and written back to the part's paint data.
#include "orca_engine/Painting.hpp"

#include <algorithm>
#include <stdexcept>

#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleSelector.hpp"

namespace orca::engine {

namespace {

using Slic3r::EnforcerBlockerType;
using Slic3r::TriangleSelector;

Slic3r::FacetsAnnotation& facets_of(Slic3r::ModelVolume& volume, PaintKind kind)
{
    switch (kind) {
    case PaintKind::Supports: return volume.supported_facets;
    case PaintKind::Seam: return volume.seam_facets;
    case PaintKind::FuzzySkin: return volume.fuzzy_skin_facets;
    case PaintKind::Color: return volume.mmu_segmentation_facets;
    }
    throw std::invalid_argument("No such paint");
}

// The highest state each kind uses, as the gizmos deserialize them.
EnforcerBlockerType max_state(PaintKind kind)
{
    switch (kind) {
    case PaintKind::Supports:
    case PaintKind::Seam: return EnforcerBlockerType::BLOCKER;
    case PaintKind::FuzzySkin: return EnforcerBlockerType::FUZZY_SKIN;
    case PaintKind::Color: return EnforcerBlockerType::ExtruderMax;
    }
    return EnforcerBlockerType::NONE;
}

Slic3r::Vec3f vec(const std::array<float, 3>& a) { return {a[0], a[1], a[2]}; }

} // namespace

struct PaintSession::Impl {
    Project& project;
    std::size_t object = 0;
    PaintKind kind = PaintKind::Supports;

    struct Part {
        std::size_t volume = 0;
        // Kept alive: the selector refers to it.
        std::shared_ptr<const Slic3r::TriangleMesh> mesh;
        std::unique_ptr<TriangleSelector> selector;
    };
    std::vector<Part> parts;

    Part* find(std::size_t volume)
    {
        for (Part& part : parts)
            if (part.volume == volume)
                return &part;
        return nullptr;
    }

    const Part* find(std::size_t volume) const { return const_cast<Impl*>(this)->find(volume); }

    Impl(Project& p, std::size_t o, PaintKind k) : project(p), object(o), kind(k) {}
};

PaintSession::PaintSession(Project& project, std::size_t object, PaintKind kind)
    : m_impl(std::make_unique<Impl>(project, object, kind))
{
    project.edit([&](Slic3r::Model& model) {
        if (object >= model.objects.size())
            throw std::out_of_range("No such object on the plate");
        const Slic3r::ModelObject& o = *model.objects[object];
        for (std::size_t v = 0; v < o.volumes.size(); ++v) {
            Slic3r::ModelVolume& volume = *o.volumes[v];
            if (!volume.is_model_part())
                continue;
            Impl::Part part;
            part.volume = v;
            part.mesh = volume.get_mesh_shared_ptr();
            part.selector = std::make_unique<TriangleSelector>(*part.mesh);
            part.selector->deserialize(facets_of(volume, kind).get_data(), false, max_state(kind));
            m_impl->parts.push_back(std::move(part));
        }
    });
}

PaintSession::~PaintSession() = default;

std::size_t PaintSession::object() const { return m_impl->object; }

PaintKind PaintSession::kind() const { return m_impl->kind; }

std::vector<std::size_t> PaintSession::paintable_volumes() const
{
    std::vector<std::size_t> volumes;
    for (const Impl::Part& part : m_impl->parts)
        volumes.push_back(part.volume);
    return volumes;
}

bool PaintSession::paint(const PaintStroke& stroke)
{
    Impl::Part* part = m_impl->find(stroke.volume);
    if (part == nullptr || stroke.via.object != m_impl->object)
        return false;
    const int facet_count = static_cast<int>(part->mesh->its.indices.size());
    if (stroke.facet < 0 || stroke.facet >= facet_count)
        return false;
    const int top = static_cast<int>(max_state(m_impl->kind));
    const auto state = static_cast<EnforcerBlockerType>(std::clamp(stroke.state, 0, top));

    // Mesh to world, as the gizmos' trafo_matrices, and without the translation.
    Slic3r::Transform3d trafo = Slic3r::Transform3d::Identity();
    m_impl->project.edit([&](Slic3r::Model& model) {
        const Slic3r::ModelObject& object = *model.objects.at(m_impl->object);
        trafo = object.instances.at(stroke.via.instance)->get_matrix() * object.volumes.at(stroke.volume)->get_matrix();
    });
    Slic3r::Transform3d no_translation = trafo;
    no_translation.translation() = Slic3r::Vec3d::Zero();

    const TriangleSelector::ClippingPlane no_clipping;
    const Slic3r::Vec3f hit = vec(stroke.hit);
    const Slic3r::Vec3f camera = vec(stroke.camera);
    TriangleSelector& selector = *part->selector;
    switch (stroke.tool) {
    case PaintTool::Circle:
    case PaintTool::Sphere: {
        const auto type = stroke.tool == PaintTool::Sphere ? TriangleSelector::SPHERE : TriangleSelector::CIRCLE;
        std::unique_ptr<TriangleSelector::Cursor> cursor;
        int start = stroke.facet;
        if (stroke.previous && stroke.previous_facet >= 0 && stroke.previous_facet < facet_count) {
            cursor = TriangleSelector::DoublePointCursor::cursor_factory(vec(*stroke.previous), hit, camera, stroke.radius_mm,
                                                                         type, trafo, no_clipping);
            start = stroke.previous_facet;
        } else {
            cursor = TriangleSelector::SinglePointCursor::cursor_factory(hit, camera, stroke.radius_mm, type, trafo, no_clipping);
        }
        selector.select_patch(start, std::move(cursor), state, no_translation, true, stroke.overhang_angle_deg);
        break;
    }
    case PaintTool::SmartFill:
        selector.seed_fill_select_triangles(hit, stroke.facet, no_translation, no_clipping, stroke.fill_angle_deg,
                                            stroke.overhang_angle_deg, Slic3r::Vec3f::UnitZ(), true);
        selector.seed_fill_apply_on_triangles(state);
        break;
    case PaintTool::BucketFill:
        selector.bucket_fill_select_triangles(hit, stroke.facet, no_clipping, stroke.fill_angle_deg, true, true);
        selector.seed_fill_apply_on_triangles(state);
        break;
    }
    return true;
}

void PaintSession::clear()
{
    for (Impl::Part& part : m_impl->parts)
        part.selector->reset();
}

void PaintSession::paint_all(int state)
{
    const int top = static_cast<int>(max_state(m_impl->kind));
    const auto value = static_cast<EnforcerBlockerType>(std::clamp(state, 0, top));
    for (Impl::Part& part : m_impl->parts) {
        part.selector->reset();
        const int count = static_cast<int>(part.mesh->its.indices.size());
        for (int facet = 0; facet < count; ++facet)
            part.selector->set_facet(facet, value);
    }
}

bool PaintSession::commit()
{
    bool changed = false;
    m_impl->project.edit([&](Slic3r::Model& model) {
        Slic3r::ModelObject& object = *model.objects.at(m_impl->object);
        for (const Impl::Part& part : m_impl->parts) {
            if (part.volume >= object.volumes.size())
                continue;
            Slic3r::ModelVolume& volume = *object.volumes[part.volume];
            // The volume must still be the one painted.
            if (volume.get_mesh_shared_ptr() != part.mesh)
                continue;
            changed |= facets_of(volume, m_impl->kind).set(*part.selector);
        }
    });
    return changed;
}

PaintOverlay PaintSession::overlay(std::size_t volume) const
{
    PaintOverlay overlay;
    const Impl::Part* part = m_impl->find(volume);
    if (part == nullptr)
        return overlay;
    // One pass per state for the two-state kinds; colours read all at once.
    std::vector<indexed_triangle_set> per_state;
    if (m_impl->kind == PaintKind::Color) {
        part->selector->get_facets(per_state);
    } else {
        per_state.resize(static_cast<std::size_t>(max_state(m_impl->kind)) + 1);
        for (std::size_t state = 1; state < per_state.size(); ++state)
            per_state[state] = part->selector->get_facets(static_cast<EnforcerBlockerType>(state));
    }
    std::size_t triangles = 0;
    for (std::size_t state = 1; state < per_state.size(); ++state) {
        const indexed_triangle_set& its = per_state[state];
        if (its.indices.empty())
            continue;
        for (const auto& face : its.indices)
            for (int k = 0; k < 3; ++k) {
                const Slic3r::Vec3f& p = its.vertices[face[k]];
                overlay.positions.insert(overlay.positions.end(), {p.x(), p.y(), p.z()});
            }
        overlay.runs.push_back({static_cast<int>(state), triangles, its.indices.size()});
        triangles += its.indices.size();
    }
    return overlay;
}

} // namespace orca::engine
