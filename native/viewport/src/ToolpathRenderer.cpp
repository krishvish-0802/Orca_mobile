#include "ToolpathRenderer.hpp"

#include <GLES3/gl3.h>
#include <android/log.h>

#include <algorithm>
#include <exception>

#include "libvgcode/include/GCodeInputData.hpp"
#include "libvgcode/include/Types.hpp"
#include "libvgcode/include/Viewer.hpp"
#include "orca_viewport/Viewport.hpp"

namespace orca::viewport {

namespace {

constexpr const char* kLogTag = "OrcaViewport";

libvgcode::Mat4x4 to_vgcode(const Eigen::Matrix4f& m)
{
    // Both column-major.
    libvgcode::Mat4x4 out{};
    std::copy(m.data(), m.data() + 16, out.begin());
    return out;
}

libvgcode::EViewType to_view_type(ToolpathColoring coloring)
{
    using V = libvgcode::EViewType;
    switch (coloring) {
    case ToolpathColoring::FeatureType: return V::FeatureType;
    case ToolpathColoring::Height: return V::Height;
    case ToolpathColoring::Width: return V::Width;
    case ToolpathColoring::Speed: return V::Speed;
    case ToolpathColoring::FanSpeed: return V::FanSpeed;
    case ToolpathColoring::Temperature: return V::Temperature;
    case ToolpathColoring::VolumetricFlowRate: return V::VolumetricFlowRate;
    case ToolpathColoring::LayerTime: return V::LayerTimeLinear;
    case ToolpathColoring::Filament: return V::Tool;
    }
    return V::FeatureType;
}

} // namespace

ToolpathRenderer::ToolpathRenderer() = default;
ToolpathRenderer::~ToolpathRenderer() = default;

void ToolpathRenderer::on_context_created()
{
    // The old viewer's GL names are dead. Deleting them now, before anything else
    // exists in the fresh context, cannot touch live objects.
    m_viewer.reset();
    m_initialized = false;
    m_loaded = false;
}

bool ToolpathRenderer::ensure_initialized()
{
    if (m_initialized)
        return true;
    m_viewer = std::make_unique<libvgcode::Viewer>();
    try {
        m_viewer->init(reinterpret_cast<const char*>(glGetString(GL_VERSION)));
        m_initialized = true;
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Toolpath renderer setup failed: %s", e.what());
        m_viewer.reset();
    }
    return m_initialized;
}

bool ToolpathRenderer::load(const libvgcode::GCodeInputData& toolpaths)
{
    if (!ensure_initialized())
        return false;
    libvgcode::GCodeInputData copy = toolpaths;   // the viewer keeps its own
    m_viewer->reset_default_extrusion_roles_colors();
    m_viewer->load(std::move(copy));
    m_loaded = m_viewer->get_layers_count() > 0;
    return m_loaded;
}

void ToolpathRenderer::release()
{
    if (m_viewer)
        m_viewer->reset();
    m_loaded = false;
}

std::size_t ToolpathRenderer::layer_count() const
{
    return m_loaded ? m_viewer->get_layers_count() : 0;
}

void ToolpathRenderer::set_layer_range(std::size_t first, std::size_t last)
{
    const std::size_t count = layer_count();
    if (count == 0)
        return;
    last = std::min(last, count - 1);
    first = std::min(first, last);
    m_viewer->set_layers_view_range(first, last);
}

void ToolpathRenderer::set_coloring(ToolpathColoring coloring)
{
    if (m_viewer)
        m_viewer->set_view_type(to_view_type(coloring));
}

std::array<Eigen::Vector3f, 2> ToolpathRenderer::bounds() const
{
    if (!m_loaded)
        return {Eigen::Vector3f::Zero(), Eigen::Vector3f::Zero()};
    const libvgcode::AABox box = m_viewer->get_extrusion_bounding_box();
    return {Eigen::Vector3f(box[0][0], box[0][1], box[0][2]), Eigen::Vector3f(box[1][0], box[1][1], box[1][2])};
}

void ToolpathRenderer::render(const Eigen::Matrix4f& view, const Eigen::Matrix4f& projection)
{
    if (!m_loaded)
        return;
    m_viewer->render(to_vgcode(view), to_vgcode(projection));
    // libvgcode leaves its own program and buffers bound.
    glUseProgram(0);
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

} // namespace orca::viewport
