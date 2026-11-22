#pragma once

// The desktop's mesh tools on the plate: Cut (GLGizmoCut3D).

#include <array>
#include <cstddef>
#include <string>
#include <vector>

#include "orca_engine/Project.hpp"

namespace orca::engine {

// --- Cut ---

// Values are OrcaSlicer's (CutConnectorType, CutConnectorStyle, CutConnectorShape).
enum class CutConnectorType : int { Plug = 0, Dowel = 1, Snap = 2 };
enum class CutConnectorStyle : int { Prism = 0, Frustum = 1 };
enum class CutConnectorShape : int { Triangle = 0, Square = 1, Hexagon = 2, Circle = 3 };

// A connector on the cut face, as the desktop's connectors editor adds one.
// A plug stands on the lower piece and fits a hole in the upper one; a dowel
// is a separate pin with a hole in both; a snap clicks into the upper one.
struct CutConnectorSpec {
    std::array<double, 2> at{};        // mm from the plane's center, along its own X and Y
    CutConnectorType type = CutConnectorType::Plug;
    CutConnectorStyle style = CutConnectorStyle::Prism;
    CutConnectorShape shape = CutConnectorShape::Circle;
    double size_mm = 5.0;              // across
    double depth_mm = 5.0;             // how far it reaches into the upper piece
    double size_tolerance_mm = 0.0;    // the hole this much wider
    double depth_tolerance_mm = 0.1;   // and this much deeper
    double rotation_deg = 0.0;         // about the plane's normal
};

// Planar, or a dovetail (the desktop's tongue and groove) along the plane.
enum class CutMode : int { Planar = 0, Dovetail = 1 };

// The dovetail's profile (Cut::Groove): angles in degrees.
struct DovetailSpec {
    double depth_mm = 0;               // 0: as the desktop sizes it for the object
    double width_mm = 0;               // 0: four times the depth
    double flaps_angle_deg = 60;
    double angle_deg = 0;              // turns the groove about the plane's normal
    double depth_tolerance_mm = 0.1;
    double width_tolerance_mm = 0.1;
    int count = 1;                     // dovetails side by side
    double gap_mm = 10;                // between them
};

// The cut plane through the instance: its center above the middle of the
// instance's box at `height` (mm above the bed), turned about X then Y.
struct CutPlane {
    double height = 0;
    double tilt_x_deg = 0;
    double tilt_y_deg = 0;
};

struct CutOptions {
    bool keep_upper = true;
    bool keep_lower = true;
    bool as_parts = false;             // one object with both pieces as parts
    bool place_on_cut_upper = false;   // stand the piece on its cut face
    bool place_on_cut_lower = false;
    bool flip_upper = false;           // turn the piece upside down
    bool flip_lower = false;
};

struct CutRequest {
    InstanceRef via;
    CutPlane plane;
    CutMode mode = CutMode::Planar;
    CutOptions options;
    std::vector<CutConnectorSpec> connectors;   // planar cuts only
    DovetailSpec dovetail;
};

// Why a connector cannot go where it is.
enum class ConnectorProblem : int { None = 0, OutsideSection = 1, Overlapping = 2, OutsideObject = 3 };

// What the cut tool draws and checks before cutting, in world coordinates.
struct CutPreview {
    std::array<double, 3> box_min{}, box_max{};  // the instance on the plate
    std::array<double, 3> center{}, normal{};    // of the plane (normal points up)
    std::array<double, 3> axis_x{}, axis_y{};    // the plane's own axes, for connector positions
    std::vector<float> plane;                    // triangles: the plane, a square around the object
    std::vector<float> section;                  // triangles: where the plane meets the parts
    std::vector<float> outline;                  // segments: the section's edges
    std::vector<float> connectors;               // triangles: every connector as it will be cut
    std::vector<ConnectorProblem> connector_problems;  // one per connector
    double dovetail_depth_mm = 0;                // what the dovetail uses when its depth is 0
    bool can_cut = false;
    std::string reason;                          // why not, when it cannot
};

// Throws std::out_of_range for a bad reference.
CutPreview cut_preview(const Project& project, const CutRequest& request);

// Where a world point on the plane is in its own coordinates (mm from its
// center), and whether that is inside the section.
struct PlanePoint {
    std::array<double, 2> at{};
    bool inside_section = false;
};
PlanePoint cut_plane_point(const Project& project, const CutRequest& request, const std::array<double, 3>& world);

// Cuts the instance's object as the desktop's Perform cut: the pieces replace
// it, standing on the bed where it was, and come last. Returns their indices.
// Throws std::invalid_argument when the cut cannot be made (nothing kept, a
// connector that does not fit, a plane that misses the object).
std::vector<std::size_t> cut(Project& project, const CutRequest& request);

} // namespace orca::engine
