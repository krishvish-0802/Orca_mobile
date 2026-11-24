#pragma once

// The desktop's Texture displacement tool (GLGizmoTextureDisplacement) without
// its gizmo: a stack of height-map layers on an object, each painted where it
// applies (PaintKind::Texture with the layer's slot), baked into real geometry.
//
// The stack lives on the object's first part, as the desktop keeps it; the
// paint reaches every part, and a bake displaces each painted part through its
// own mesh with the same layers.

#include <cstddef>
#include <functional>
#include <optional>
#include <string>
#include <vector>

#include "orca_engine/Project.hpp"

namespace orca::engine {

// How a layer's texture is laid onto the surface (TextureProjectionMethod).
enum class TextureProjection : int { Triplanar = 0, Cylindrical = 1, Spherical = 2, Unwrap = 3, ModelUv = 5 };

// How a layer combines with the layers below it (TextureBlendMode).
enum class TextureBlend : int { Add = 0, Subtract = 1, Multiply = 2, Divide = 3, Override = 4 };

// A layer's settings, as the desktop panel edits them. Lengths in mm.
struct TextureLayerSettings {
    float depth_mm = 0.4f;
    float tile_mm = 12.5f;
    float rotation_deg = 0.f;
    float offset_x = 0.f;
    float offset_y = 0.f;
    bool invert = false;
    // The height that stays put: 0 only raises, 0.5 also cuts in where darker.
    float midlevel = 0.f;
    // Softens the height map, 0..1.
    float smoothing = 0.f;
    // Fades the relief out toward the edge of the paint, by `edge_fade` (0..1).
    bool fade_edge = false;
    float edge_fade = 0.5f;
    // Repeats the texture (mirrored every other tile when `mirror_tiles`); off, it is laid once.
    bool tiled = true;
    bool mirror_tiles = false;
    TextureProjection projection = TextureProjection::Triplanar;
    TextureBlend blend = TextureBlend::Add;
};

struct TextureLayer {
    int slot = -1;
    std::string name;
    // The image file it came from (may be stale on a project from elsewhere).
    std::string path;
    int width = 0;
    int height = 0;
    bool has_colour = false;
    // Some part of the object is painted with this layer.
    bool painted = false;
    TextureLayerSettings settings;
};

// The stack and the settings that apply to all of it (TextureDisplacementOptions).
struct TextureStack {
    std::vector<TextureLayer> layers;
    // Laplacian smoothing of the relief after the layers are applied.
    bool smooth = false;
    float smooth_strength = 0.3f;
    int smooth_passes = 2;
    // Displaces the paint's outermost vertices too (off pins them flat).
    bool displace_border = true;
    // The object's parts carry a UV map of their own (ModelUv projection).
    bool has_model_uv = false;
    // Room for another layer.
    bool full = false;
};

// What a bake did.
struct TextureBakeResult {
    std::size_t parts = 0;
    std::size_t triangles_before = 0;
    std::size_t triangles_after = 0;
    // The triangle budget took detail back out to fit.
    bool budget_limited = false;
};

// One of the textures OrcaSlicer ships (resources/textures/displacement).
struct LibraryTexture {
    std::string name;
    std::string path;
};

// Throws std::out_of_range for a bad object.
TextureStack texture_stack(const Project& project, std::size_t object);

// A layer with the image at `image_path` (a PNG, grey or colour); returns its slot.
// Throws std::invalid_argument for an image that is not a PNG, std::length_error
// when the stack is full.
int add_texture_layer(Project& project, std::size_t object, const std::string& image_path, const std::string& name);
// The layer's image swapped for another; its settings and paint stay.
void set_texture_image(Project& project, std::size_t object, int slot, const std::string& image_path, const std::string& name);
void set_texture_layer(Project& project, std::size_t object, int slot, const TextureLayerSettings& settings);
// Removes the layer and its paint on every part.
void remove_texture_layer(Project& project, std::size_t object, int slot);
// Moves the layer `by` places up (later, on top) or down the stack.
void move_texture_layer(Project& project, std::size_t object, int slot, int by);
void set_texture_options(Project& project, std::size_t object, bool smooth, float smooth_strength, int smooth_passes,
                         bool displace_border);

// Displaces every painted part by the stack (TextureDisplacementBakeJob): the
// relief becomes the parts' meshes, their other paint is carried over and the
// layers' paint is cleared; the layers stay for another bake. The work runs
// outside the project's lock; a part changed meanwhile is left alone.
// `progress` gets 0..100 and returns false to stop (nothing is changed then).
// Throws std::invalid_argument when nothing is painted or nothing would move.
TextureBakeResult bake_texture(Project& project, std::size_t object, const std::function<bool(int)>& progress = {});

std::vector<LibraryTexture> texture_library();

} // namespace orca::engine
