#pragma once

namespace orca::viewport::shaders {

// Lit triangles (model, bed plate): two directional lights plus ambient, so
// faces stay readable from every side.
inline constexpr const char* kLitVertex = R"(#version 300 es
uniform mat4 u_mvp;
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec3 a_normal;
out vec3 v_normal;
out vec3 v_world;
void main() {
    v_normal = a_normal;
    v_world = a_position;
    gl_Position = u_mvp * vec4(a_position, 1.0);
}
)";

inline constexpr const char* kLitFragment = R"(#version 300 es
precision mediump float;
uniform vec4 u_color;
uniform vec3 u_eye;
in vec3 v_normal;
in vec3 v_world;
out vec4 frag_color;
void main() {
    vec3 n = normalize(v_normal);
    // Light both faces of open meshes.
    if (dot(n, u_eye - v_world) < 0.0) n = -n;
    float key = max(dot(n, normalize(vec3(-0.4, -0.6, 0.7))), 0.0);
    float fill = max(dot(n, normalize(vec3(0.6, 0.3, 0.4))), 0.0);
    float light = 0.32 + 0.55 * key + 0.25 * fill;
    frag_color = vec4(u_color.rgb * light, u_color.a);
}
)";

// Model parts: object coordinates placed by u_model. Flat shading from screen
// derivatives, so any placement (mirrored, scaled) lights correctly without normals.
inline constexpr const char* kPartVertex = R"(#version 300 es
uniform mat4 u_view_proj;
uniform mat4 u_model;
layout(location = 0) in vec3 a_position;
out highp vec3 v_world;
void main() {
    vec4 world = u_model * vec4(a_position, 1.0);
    v_world = world.xyz;
    gl_Position = u_view_proj * world;
}
)";

// A cut plane (u_plane, zero when there is none) colors what lies above it with
// u_color_above, or hides it (u_clip_above) so the cut face shows.
inline constexpr const char* kPartFragment = R"(#version 300 es
precision highp float;
uniform vec4 u_color;
uniform vec4 u_plane;
uniform vec4 u_color_above;
uniform float u_clip_above;
uniform vec3 u_eye;
in vec3 v_world;
out vec4 frag_color;
void main() {
    bool above = dot(vec4(v_world, 1.0), u_plane) > 0.0;
    if (above && u_clip_above > 0.5) discard;
    vec3 n = normalize(cross(dFdx(v_world), dFdy(v_world)));
    if (dot(n, u_eye - v_world) < 0.0) n = -n;
    float key = max(dot(n, normalize(vec3(-0.4, -0.6, 0.7))), 0.0);
    float fill = max(dot(n, normalize(vec3(0.6, 0.3, 0.4))), 0.0);
    float light = 0.32 + 0.55 * key + 0.25 * fill;
    vec4 color = above ? u_color_above : u_color;
    frag_color = vec4(color.rgb * light, color.a);
}
)";

// Unlit lines (bed grid and outline).
inline constexpr const char* kLineVertex = R"(#version 300 es
uniform mat4 u_mvp;
layout(location = 0) in vec3 a_position;
void main() {
    gl_Position = u_mvp * vec4(a_position, 1.0);
}
)";

inline constexpr const char* kLineFragment = R"(#version 300 es
precision mediump float;
uniform vec4 u_color;
out vec4 frag_color;
void main() {
    frag_color = u_color;
}
)";

} // namespace orca::viewport::shaders
