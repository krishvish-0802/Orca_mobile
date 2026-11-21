#pragma once

#include <Eigen/Geometry>

namespace orca::viewport {

// An orbit camera around a target point, Z up. Angles in radians.
class Camera {
public:
    using Vec3 = Eigen::Vector3f;
    using Mat4 = Eigen::Matrix4f;

    void set_viewport(int width_px, int height_px);

    void orbit(float dx_px, float dy_px);
    void pan(float dx_px, float dy_px);
    // factor > 1 moves closer. The point under the focus stays under it.
    void zoom(float factor, float focus_x_px, float focus_y_px);
    // Frames a sphere around `center`.
    void frame(const Vec3& center, float radius);

    // The ray through a screen point: origin at the eye, unit direction.
    void ray(float x_px, float y_px, Vec3& origin, Vec3& direction) const;

    Mat4 view() const;
    Mat4 projection() const;
    Vec3 eye() const;

private:
    Vec3 forward() const;
    // World units per pixel on the plane through the target facing the camera.
    float world_per_pixel() const;

    Vec3 m_target{0, 0, 0};
    float m_distance = 400;
    float m_yaw = -1.5707963f;      // looking along +Y, from the front of the bed
    float m_pitch = 0.6f;           // above the bed
    float m_fov_y = 0.6108652f;     // 35 degrees
    int m_width = 1;
    int m_height = 1;
};

} // namespace orca::viewport
