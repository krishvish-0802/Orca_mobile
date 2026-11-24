// The desktop's Texture displacement tool (GLGizmoTextureDisplacement and
// TextureDisplacementBakeJob) without its gizmo or job worker.
#include "orca_engine/TextureDisplacement.hpp"

#include <algorithm>
#include <array>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <limits>
#include <memory>
#include <stdexcept>

#include "MeshToolsCommon.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/TextureDisplacement.hpp"
#include "libslic3r/TriangleSelector.hpp"
#include "libslic3r/Utils.hpp"

namespace orca::engine {

namespace {

using Slic3r::ModelObject;
using Slic3r::ModelVolume;
using Slic3r::TEXTURE_DISPLACEMENT_MAX_LAYERS;
using Slic3r::TextureDisplacementLayer;

// GLGizmoTextureDisplacement::texture_parts: the object's model parts.
std::vector<ModelVolume*> parts_of(const ModelObject& object)
{
    std::vector<ModelVolume*> out;
    for (ModelVolume* volume : object.volumes)
        if (volume->is_model_part())
            out.push_back(volume);
    return out;
}

// texture_volume(): the first part holds the stack.
ModelVolume& owner_of(const ModelObject& object)
{
    for (ModelVolume* volume : object.volumes)
        if (volume->is_model_part())
            return *volume;
    throw std::invalid_argument("The object has no part to texture");
}

TextureDisplacementLayer& layer_at(ModelVolume& owner, int slot)
{
    for (TextureDisplacementLayer& layer : owner.texture_displacement_layers)
        if (layer.slot == slot)
            return layer;
    throw std::out_of_range("No such texture layer");
}

bool is_png(const std::vector<unsigned char>& bytes)
{
    static constexpr unsigned char signature[] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    return bytes.size() > sizeof(signature) && std::equal(std::begin(signature), std::end(signature), bytes.begin());
}

std::shared_ptr<std::vector<unsigned char>> read_png(const std::string& path)
{
    std::ifstream in(path, std::ios::binary);
    if (!in)
        throw std::invalid_argument("The texture image could not be read");
    auto bytes = std::make_shared<std::vector<unsigned char>>(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    if (!is_png(*bytes))
        throw std::invalid_argument("The texture image is not a PNG");
    return bytes;
}

void set_image(TextureDisplacementLayer& layer, const std::string& path, const std::string& name)
{
    TextureDisplacementLayer probe;
    probe.image_data = read_png(path);
    if (Slic3r::decode_height_texture(probe, false).empty())
        throw std::invalid_argument("The texture image could not be decoded");
    layer.image_data = std::move(probe.image_data);
    layer.path = path;
    layer.path_in_3mf.clear();
    layer.name = name.empty() ? std::filesystem::path(path).stem().string() : name;
}

TextureLayerSettings settings_of(const TextureDisplacementLayer& l)
{
    TextureLayerSettings s;
    s.depth_mm = l.depth_mm;
    s.tile_mm = l.tiling_scale;
    s.rotation_deg = l.rotation_deg;
    s.offset_x = l.offset.x();
    s.offset_y = l.offset.y();
    s.invert = l.invert;
    s.midlevel = l.midlevel;
    s.smoothing = l.smoothing;
    s.fade_edge = l.edge_smoothing;
    s.edge_fade = l.edge_smoothing_amount;
    s.tiled = l.tile_enabled;
    s.mirror_tiles = l.tile_method == Slic3r::TextureTileMethod::MirroredRepeat;
    switch (l.projection_method) {
    case Slic3r::TextureProjectionMethod::Cylindrical: s.projection = TextureProjection::Cylindrical; break;
    case Slic3r::TextureProjectionMethod::Spherical: s.projection = TextureProjection::Spherical; break;
    case Slic3r::TextureProjectionMethod::LSCM: s.projection = TextureProjection::Unwrap; break;
    case Slic3r::TextureProjectionMethod::ModelUV: s.projection = TextureProjection::ModelUv; break;
    // A view projection made on the desktop shows as tri-planar here; it is kept until changed.
    default: s.projection = TextureProjection::Triplanar; break;
    }
    s.blend = static_cast<TextureBlend>(std::clamp(static_cast<int>(l.blend_mode), 0, 4));
    return s;
}

void apply(TextureDisplacementLayer& l, const TextureLayerSettings& s)
{
    l.depth_mm = std::clamp(s.depth_mm, 0.f, 20.f);
    l.tiling_scale = std::clamp(s.tile_mm, 0.1f, 1000.f);
    l.rotation_deg = s.rotation_deg;
    l.offset = Slic3r::Vec2f(s.offset_x, s.offset_y);
    l.invert = s.invert;
    l.midlevel = std::clamp(s.midlevel, 0.f, 1.f);
    l.smoothing = std::clamp(s.smoothing, 0.f, 1.f);
    l.edge_smoothing = s.fade_edge;
    l.edge_smoothing_amount = std::clamp(s.edge_fade, 0.01f, 1.f);
    l.tile_enabled = s.tiled;
    l.tile_method = s.mirror_tiles ? Slic3r::TextureTileMethod::MirroredRepeat : Slic3r::TextureTileMethod::Repeat;
    const auto current = settings_of(l).projection;
    if (s.projection != current) {
        switch (s.projection) {
        case TextureProjection::Triplanar: l.projection_method = Slic3r::TextureProjectionMethod::Triplanar; break;
        case TextureProjection::Cylindrical: l.projection_method = Slic3r::TextureProjectionMethod::Cylindrical; break;
        case TextureProjection::Spherical: l.projection_method = Slic3r::TextureProjectionMethod::Spherical; break;
        case TextureProjection::Unwrap: l.projection_method = Slic3r::TextureProjectionMethod::LSCM; break;
        case TextureProjection::ModelUv: l.projection_method = Slic3r::TextureProjectionMethod::ModelUV; break;
        }
    }
    l.blend_mode = static_cast<Slic3r::TextureBlendMode>(std::clamp(static_cast<int>(s.blend), 0, 4));
}

bool slot_painted(const ModelObject& object, int slot)
{
    for (const ModelVolume* part : parts_of(object))
        if (!part->texture_displacement_facet(slot).empty())
            return true;
    return false;
}

// GLGizmoTextureDisplacement::layers_for_part: a part other than the owner
// takes the stack without the owner's hand-made unwrap edits (its charts differ).
std::vector<TextureDisplacementLayer> layers_for(const ModelVolume& owner, const ModelVolume& part)
{
    std::vector<TextureDisplacementLayer> out = owner.texture_displacement_layers;
    if (&part == &owner)
        return out;
    for (TextureDisplacementLayer& layer : out) {
        layer.lscm_seam_edges.clear();
        layer.islands.clear();
        layer.island_groups.clear();
        layer.lscm_uv_overrides.clear();
    }
    return out;
}

// TextureDisplacementBakeVolume: one part's share, captured under the lock.
struct BakeInput {
    std::size_t volume = 0;
    std::shared_ptr<const Slic3r::TriangleMesh> mesh;
    std::vector<TextureDisplacementLayer> layers;
    Slic3r::TextureDisplacementFacetsData facets;
    Slic3r::TextureDisplacementOptions options;
    Slic3r::Transform3d volume_to_world = Slic3r::Transform3d::Identity();
    std::vector<Slic3r::Vec2f> model_uv;
    Slic3r::Vec3d anchor = Slic3r::Vec3d::Zero();
    float resting_drop = 0.f;
};

} // namespace

TextureStack texture_stack(const Project& project, std::size_t object_index)
{
    TextureStack stack;
    project.inspect([&](const Slic3r::Model& model) {
        const ModelObject& object = detail::object_at(model, object_index);
        const ModelVolume& owner = owner_of(object);
        for (const TextureDisplacementLayer& l : owner.texture_displacement_layers) {
            if (l.slot < 0 || l.slot >= int(TEXTURE_DISPLACEMENT_MAX_LAYERS))
                continue;
            TextureLayer layer;
            layer.slot = l.slot;
            layer.name = l.name;
            layer.path = l.path;
            if (!l.empty()) {
                const Slic3r::DecodedHeightTexture decoded = Slic3r::decode_height_texture(l);
                layer.width = decoded.width;
                layer.height = decoded.height;
                layer.has_colour = decoded.has_color();
            }
            layer.painted = slot_painted(object, l.slot);
            layer.settings = settings_of(l);
            stack.layers.push_back(std::move(layer));
        }
        const Slic3r::TextureDisplacementOptions& o = owner.texture_displacement_options;
        stack.smooth = o.smooth_enabled;
        stack.smooth_strength = o.smooth_strength;
        stack.smooth_passes = o.smooth_iterations;
        stack.displace_border = o.displace_border;
        for (const ModelVolume* part : parts_of(object))
            stack.has_model_uv |= part->has_texture_uv();
        stack.full = owner.texture_displacement_layers.size() >= TEXTURE_DISPLACEMENT_MAX_LAYERS;
    });
    return stack;
}

int add_texture_layer(Project& project, std::size_t object_index, const std::string& image_path, const std::string& name)
{
    // Read before the lock: the file may be large.
    TextureDisplacementLayer layer;
    set_image(layer, image_path, name);
    int slot = -1;
    project.edit([&](Slic3r::Model& model) {
        ModelObject& object = detail::object_at(model, object_index);
        ModelVolume& owner = owner_of(object);
        // add_texture_layer: the first slot no layer uses.
        std::array<bool, TEXTURE_DISPLACEMENT_MAX_LAYERS> used{};
        for (const TextureDisplacementLayer& l : owner.texture_displacement_layers)
            if (l.slot >= 0 && std::size_t(l.slot) < used.size())
                used[std::size_t(l.slot)] = true;
        for (std::size_t i = 0; i < used.size(); ++i)
            if (!used[i]) {
                slot = int(i);
                break;
            }
        if (slot < 0)
            throw std::length_error("The object has as many texture layers as it can hold");
        layer.slot = slot;
        // A model with its own UV map is mapped by it.
        for (const ModelVolume* part : parts_of(object))
            if (part->has_texture_uv())
                layer.projection_method = Slic3r::TextureProjectionMethod::ModelUV;
        // A slot freed earlier may still hold paint from a removed layer.
        for (ModelVolume* part : parts_of(object))
            part->texture_displacement_facet(slot).reset();
        owner.texture_displacement_layers.push_back(std::move(layer));
        owner.texture_displacement_options.active_slot = slot;
    });
    return slot;
}

void set_texture_image(Project& project, std::size_t object_index, int slot, const std::string& image_path, const std::string& name)
{
    TextureDisplacementLayer probe;
    set_image(probe, image_path, name);
    project.edit([&](Slic3r::Model& model) {
        TextureDisplacementLayer& layer = layer_at(owner_of(detail::object_at(model, object_index)), slot);
        layer.image_data = std::move(probe.image_data);
        layer.path = probe.path;
        layer.path_in_3mf.clear();
        layer.name = probe.name;
        layer.preview_adjust = {};
    });
}

void set_texture_layer(Project& project, std::size_t object_index, int slot, const TextureLayerSettings& settings)
{
    project.edit([&](Slic3r::Model& model) {
        apply(layer_at(owner_of(detail::object_at(model, object_index)), slot), settings);
    });
}

void remove_texture_layer(Project& project, std::size_t object_index, int slot)
{
    project.edit([&](Slic3r::Model& model) {
        ModelObject& object = detail::object_at(model, object_index);
        ModelVolume& owner = owner_of(object);
        auto& layers = owner.texture_displacement_layers;
        const auto it = std::find_if(layers.begin(), layers.end(), [slot](const TextureDisplacementLayer& l) { return l.slot == slot; });
        if (it == layers.end())
            throw std::out_of_range("No such texture layer");
        layers.erase(it);
        for (ModelVolume* part : parts_of(object))
            part->texture_displacement_facet(slot).reset();
        if (owner.texture_displacement_options.active_slot == slot)
            owner.texture_displacement_options.active_slot = layers.empty() ? 0 : layers.front().slot;
    });
}

void move_texture_layer(Project& project, std::size_t object_index, int slot, int by)
{
    project.edit([&](Slic3r::Model& model) {
        auto& layers = owner_of(detail::object_at(model, object_index)).texture_displacement_layers;
        const auto it = std::find_if(layers.begin(), layers.end(), [slot](const TextureDisplacementLayer& l) { return l.slot == slot; });
        if (it == layers.end())
            throw std::out_of_range("No such texture layer");
        const long from = long(it - layers.begin());
        const long to = std::clamp(from + by, 0L, long(layers.size()) - 1);
        if (to > from)
            std::rotate(layers.begin() + from, layers.begin() + from + 1, layers.begin() + to + 1);
        else if (to < from)
            std::rotate(layers.begin() + to, layers.begin() + from, layers.begin() + from + 1);
    });
}

void set_texture_options(Project& project, std::size_t object_index, bool smooth, float smooth_strength, int smooth_passes,
                         bool displace_border)
{
    project.edit([&](Slic3r::Model& model) {
        Slic3r::TextureDisplacementOptions& o = owner_of(detail::object_at(model, object_index)).texture_displacement_options;
        o.smooth_enabled = smooth;
        o.smooth_strength = std::clamp(smooth_strength, 0.f, 1.f);
        o.smooth_iterations = std::clamp(smooth_passes, 1, 10);
        o.displace_border = displace_border;
    });
}

TextureBakeResult bake_texture(Project& project, std::size_t object_index, const std::function<bool(int)>& progress)
{
    // GLGizmoTextureDisplacement::bake: every painted part, captured by value.
    std::vector<BakeInput> inputs;
    project.inspect([&](const Slic3r::Model& model) {
        const ModelObject& object = detail::object_at(model, object_index);
        const ModelVolume& owner = owner_of(object);
        const std::vector<ModelVolume*> parts = parts_of(object);
        // texture_anchor and resting_drops.
        const Slic3r::Vec3d anchor = Slic3r::texture_displacement_volume_to_world(owner).translation();
        const auto bottom = [](const ModelVolume& part) {
            return part.mesh().transformed_bounding_box(Slic3r::texture_displacement_volume_to_world(part)).min.z();
        };
        double object_bottom = std::numeric_limits<double>::max();
        for (const ModelVolume* part : parts)
            object_bottom = std::min(object_bottom, bottom(*part));
        for (std::size_t v = 0; v < object.volumes.size(); ++v) {
            const ModelVolume& part = *object.volumes[v];
            if (!part.is_model_part() || !part.is_texture_displacement_painted())
                continue;
            BakeInput in;
            in.volume = v;
            in.mesh = part.get_mesh_shared_ptr();
            in.layers = layers_for(owner, part);
            in.options = owner.texture_displacement_options;
            in.volume_to_world = Slic3r::texture_displacement_volume_to_world(part);
            if (part.has_texture_uv())
                in.model_uv = part.texture_uv;
            for (int i = 0; i < int(TEXTURE_DISPLACEMENT_MAX_LAYERS); ++i)
                in.facets[std::size_t(i)] = part.texture_displacement_facet(i).get_data();
            in.anchor = anchor;
            in.resting_drop = float(bottom(part) - object_bottom);
            inputs.push_back(std::move(in));
        }
    });
    if (inputs.empty())
        throw std::invalid_argument("Nothing is painted, there is nothing to bake");

    // TextureDisplacementBakeJob::process, outside the lock.
    std::vector<Slic3r::TriangleMesh> results(inputs.size());
    std::vector<Slic3r::TextureBakeStats> stats(inputs.size());
    for (std::size_t i = 0; i < inputs.size(); ++i) {
        const BakeInput& in = inputs[i];
        bool stopped = false;
        results[i] = Slic3r::TriangleMesh(Slic3r::build_texture_displacement(
            in.mesh->its, in.layers, in.facets, in.options,
            [&](int percent) {
                if (progress && !progress(int((i * 100 + std::size_t(std::max(0, percent))) / inputs.size()))) {
                    stopped = true;
                    return false;
                }
                return true;
            },
            nullptr, in.volume_to_world, nullptr, &stats[i], in.model_uv.empty() ? nullptr : &in.model_uv, &in.anchor,
            in.resting_drop));
        if (stopped)
            return {};
    }

    // finalize(): only a part the bake actually moved.
    const auto moved = [&](std::size_t i) {
        const indexed_triangle_set& out = results[i].its;
        const indexed_triangle_set& base = inputs[i].mesh->its;
        if (out.indices.empty())
            return false;
        bool unchanged = out.indices.size() == base.indices.size() && out.vertices.size() == base.vertices.size();
        for (std::size_t v = 0; unchanged && v < out.vertices.size(); ++v)
            unchanged = (out.vertices[v] - base.vertices[v]).cwiseAbs().maxCoeff() < 1e-5f;
        return !unchanged;
    };
    std::vector<std::size_t> commit;
    for (std::size_t i = 0; i < results.size(); ++i)
        if (moved(i))
            commit.push_back(i);
    if (commit.empty())
        throw std::invalid_argument("The bake moved nothing: check that the painted layer has a texture and a depth above zero");

    TextureBakeResult result;
    project.edit([&](Slic3r::Model& model) {
        ModelObject& object = detail::object_at(model, object_index);
        for (const std::size_t i : commit) {
            const BakeInput& in = inputs[i];
            // A part replaced since the bake started is left alone.
            if (in.volume >= object.volumes.size() || object.volumes[in.volume]->get_mesh_shared_ptr() != in.mesh)
                continue;
            ModelVolume& volume = *object.volumes[in.volume];
            const indexed_triangle_set& base = in.mesh->its;
            const bool topology_changed = in.options.pipeline_v2 || results[i].its.indices.size() != base.indices.size();

            // The volume's other paint carried across the rebuilt triangles (transfer_facet_paint).
            std::array<Slic3r::TriangleSelector::TriangleSplittingData, 4> before;
            if (topology_changed) {
                before[0] = volume.supported_facets.get_data();
                before[1] = volume.seam_facets.get_data();
                before[2] = volume.mmu_segmentation_facets.get_data();
                before[3] = volume.fuzzy_skin_facets.get_data();
            }
            result.triangles_before += base.indices.size();
            result.triangles_after += results[i].its.indices.size();
            result.budget_limited |= stats[i].budget_limited;
            volume.set_mesh(std::move(results[i]));
            volume.set_new_unique_id();
            volume.calculate_convex_hull();
            volume.invalidate_convex_hull_2d();
            if (topology_changed) {
                const std::array<Slic3r::FacetsAnnotation*, 4> target{&volume.supported_facets, &volume.seam_facets,
                                                                      &volume.mmu_segmentation_facets, &volume.fuzzy_skin_facets};
                for (std::size_t ch = 0; ch < target.size(); ++ch) {
                    const std::vector<uint8_t> state = Slic3r::transfer_facet_paint(base, before[ch], volume.mesh().its);
                    if (state.empty()) {
                        target[ch]->reset();
                        continue;
                    }
                    Slic3r::TriangleSelector selector(volume.mesh());
                    bool any = false;
                    for (std::size_t t = 0; t < state.size(); ++t)
                        if (state[t] != 0) {
                            selector.set_facet(int(t), Slic3r::EnforcerBlockerType(state[t]));
                            any = true;
                        }
                    if (any)
                        target[ch]->set(selector);
                    else
                        target[ch]->reset();
                }
            }
            // The baked layers' paint no longer describes this surface; the layers stay.
            for (int slot = 0; slot < int(TEXTURE_DISPLACEMENT_MAX_LAYERS); ++slot) {
                const auto it = std::find_if(in.layers.begin(), in.layers.end(),
                                             [slot](const TextureDisplacementLayer& l) { return l.slot == slot; });
                if (topology_changed || (it != in.layers.end() && !it->empty()))
                    volume.texture_displacement_facet(slot).reset();
            }
            if (!in.model_uv.empty())
                volume.texture_uv = topology_changed ? Slic3r::transfer_uv_channel(base, in.model_uv, volume.mesh().its) : in.model_uv;
            ++result.parts;
        }
        detail::settle_on_bed(object);
    });
    if (result.parts == 0)
        throw std::runtime_error("The object changed while it was baking; nothing was changed");
    return result;
}

std::vector<LibraryTexture> texture_library()
{
    std::vector<LibraryTexture> out;
    const std::filesystem::path dir = std::filesystem::path(Slic3r::resources_dir()) / "textures" / "displacement";
    std::error_code error;
    for (const auto& entry : std::filesystem::directory_iterator(dir, error)) {
        if (!entry.is_regular_file() || entry.path().extension() != ".png")
            continue;
        out.push_back({entry.path().stem().string(), entry.path().string()});
    }
    std::sort(out.begin(), out.end(), [](const LibraryTexture& a, const LibraryTexture& b) { return a.name < b.name; });
    return out;
}

} // namespace orca::engine
