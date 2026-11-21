#include "Camera.hpp"

#include <algorithm>
#include <cmath>

namespace orca::viewport {

namespace {
constexpr float kOrbitRadiansPerPixel = 0.008f;
constexpr float kMinPitch = -0.15f;          // allow a peek just under the bed
constexpr float kMaxPitch = 1.5533430f;      // 89 degrees
constexpr float kMinDistance = 5.0f;
constexpr float kMaxDistance = 5000.0f;
} // namespace

void Camera::set_viewport(int width_px, int height_px)
{
    m_width = std::max(width_px, 1);
    m_height = std::max(height_px, 1);
}

Camera::Vec3 Camera::forward() const
{
    // From the eye towards the target.
    return -Vec3(std::cos(m_pitch) * std::cos(m_yaw), std::cos(m_pitch) * std::sin(m_yaw), std::sin(m_pitch));
}

Camera::Vec3 Camera::eye() const { return m_target - forward() * m_distance; }

float Camera::world_per_pixel() const
{
    return 2.0f * m_distance * std::tan(m_fov_y * 0.5f) / static_cast<float>(m_height);
}

void Camera::orbit(float dx_px, float dy_px)
{
    m_yaw -= dx_px * kOrbitRadiansPerPixel;
    m_pitch = std::clamp(m_pitch + dy_px * kOrbitRadiansPerPixel, kMinPitch, kMaxPitch);
}

void Camera::pan(float dx_px, float dy_px)
{
    const Vec3 f = forward();
    const Vec3 right = f.cross(Vec3::UnitZ()).normalized();
    const Vec3 up = right.cross(f);
    const float wpp = world_per_pixel();
    // The scene follows the fingers: the target moves the opposite way.
    m_target += (-right * dx_px + up * dy_px) * wpp;
}

void Camera::zoom(float factor, float focus_x_px, float focus_y_px)
{
    if (!(factor > 0))
        return;
    const Vec3 f = forward();
    const Vec3 right = f.cross(Vec3::UnitZ()).normalized();
    const Vec3 up = right.cross(f);
    const float wpp = world_per_pixel();
    const Vec3 focus_offset =
        right * (focus_x_px - m_width * 0.5f) * wpp - up * (focus_y_px - m_height * 0.5f) * wpp;

    const float new_distance = std::clamp(m_distance / factor, kMinDistance, kMaxDistance);
    const float applied = m_distance / new_distance;
    m_distance = new_distance;
    // Shift the target so the world point under the focus stays put.
    m_target += focus_offset * (1.0f - 1.0f / applied);
}

void Camera::frame(const Vec3& center, float radius)
{
    m_target = center;
    const float half_fov = std::min(m_fov_y, m_fov_y * m_width / m_height) * 0.5f;
    m_distance = std::clamp(std::max(radius, 1.0f) / std::sin(half_fov) * 1.1f, kMinDistance, kMaxDistance);
}

Camera::Mat4 Camera::view() const
{
    const Vec3 e = eye();
    const Vec3 f = forward().normalized();
    const Vec3 s = f.cross(Vec3::UnitZ()).normalized();
    const Vec3 u = s.cross(f);
    Mat4 m = Mat4::Identity();
    m.block<1, 3>(0, 0) = s.transpose();
    m.block<1, 3>(1, 0) = u.transpose();
    m.block<1, 3>(2, 0) = -f.transpose();
    m(0, 3) = -s.dot(e);
    m(1, 3) = -u.dot(e);
    m(2, 3) = f.dot(e);
    return m;
}

Camera::Mat4 Camera::projection() const
{
    const float aspect = static_cast<float>(m_width) / static_cast<float>(m_height);
    const float near_plane = std::max(m_distance * 0.01f, 0.5f);
    const float far_plane = m_distance * 20.0f + 1000.0f;
    const float t = 1.0f / std::tan(m_fov_y * 0.5f);
    Mat4 p = Mat4::Zero();
    p(0, 0) = t / aspect;
    p(1, 1) = t;
    p(2, 2) = (far_plane + near_plane) / (near_plane - far_plane);
    p(2, 3) = 2.0f * far_plane * near_plane / (near_plane - far_plane);
    p(3, 2) = -1.0f;
    return p;
}

} // namespace orca::viewport
