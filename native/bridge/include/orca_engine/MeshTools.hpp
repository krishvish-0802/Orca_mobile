#pragma once

// The desktop's mesh tools on the plate: Cut (GLGizmoCut3D), Simplify
// (GLGizmoSimplify), Mesh boolean (GLGizmoMeshBoolean), and Measure and
// Assembly (GLGizmoMeasure, GLGizmoAssembly).

#include <array>
#include <cstddef>
#include <optional>
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

// --- Simplify ---

// The desktop's Simplify: down to a detail level (how far the shape may move),
// or by a share of the triangles.
struct SimplifyRequest {
    std::size_t object = 0;
    std::optional<std::size_t> volume;   // none: every part of the object
    bool by_ratio = false;
    double ratio_percent = 50;           // the share of triangles to remove (the desktop's "Decimate ratio")
    int detail = 2;                      // 0 extra high, 1 high, 2 medium, 3 low, 4 extra low
};

struct SimplifyOutcome {
    std::size_t before = 0;              // triangles of the parts simplified
    std::size_t after = 0;
    std::vector<float> wireframe;        // preview only: the result's edges as segments, world, first instance
};

// What simplifying would leave, without changing the model. The wireframe is
// left out past kMaxWireframeTriangles. Throws std::out_of_range for a bad index.
SimplifyOutcome simplify_preview(const Project& project, const SimplifyRequest& request);
// Simplifies, keeping paint, and leaves the object on the bed.
SimplifyOutcome simplify(Project& project, const SimplifyRequest& request);

constexpr std::size_t kMaxWireframeTriangles = 400000;

// --- Mesh boolean ---

enum class BooleanOperation : int { Union = 0, Difference = 1, Intersection = 2 };

// Two volumes of one object combined as the desktop's Mesh boolean: the result
// takes the source's place, name, kind and settings. A union always uses up the
// tool; a difference or intersection keeps it unless `delete_tool`.
struct BooleanRequest {
    std::size_t object = 0;
    std::size_t source = 0;
    std::size_t tool = 0;
    BooleanOperation operation = BooleanOperation::Union;
    bool delete_tool = false;
};

// Returns the result's index in the object's volumes. Throws std::invalid_argument
// when the volumes are the same or the operation leaves nothing.
std::size_t mesh_boolean(Project& project, const BooleanRequest& request);

// --- Measure and assembly ---

// A tap on a part, as the plate's surface pick gives it: the triangle hit
// (index in the volume's mesh) and where, in the volume's own coordinates.
// Picks stay valid when the part moves, so features follow it.
struct FeaturePick {
    InstanceRef via;
    std::size_t volume = 0;
    int facet = 0;
    std::array<double, 3> hit{};
};

// Values are libslic3r's SurfaceFeatureType.
enum class FeatureKind : int { Point = 1, Edge = 2, Circle = 4, Plane = 8 };

// What a tap may pick: any feature near it (the desktop's feature selection),
// only whole planes (face to face assembly), or only points and circles
// (point to point assembly).
enum class FeatureFilter : int { Any = 0, Planes = 1, PointsAndCircles = 2 };

// A feature on the plate, world mm: a point (a); an edge (a to b); a circle
// (center a, normal b, radius); a plane (normal a, a point on it b, and its
// triangles for drawing).
struct MeasuredFeature {
    FeatureKind kind = FeatureKind::Point;
    std::array<double, 3> a{}, b{};
    double radius = 0;
    std::vector<float> plane;
};

// GLGizmoMeasure's results between two features.
struct Measurement {
    std::optional<double> angle_deg;
    std::optional<double> distance;               // the shortest between them
    std::array<double, 3> from{}, to{};           // where it is measured
    std::optional<double> distance_infinite;      // to the line or plane through the other
    std::optional<std::array<double, 3>> xyz;     // second minus first, for points and circles
};

// What the Assembly tool can do with two features on different parts
// (Measure::get_assembly_action), and whether they are in one object.
struct AssemblyOptions {
    bool different_parts = false;
    bool same_object = false;
    bool parallel = false;
    bool center_coincidence = false;
    bool flip_first = false;
    bool flip_second = false;
    bool rotate_around = false;
    bool has_parallel_distance = false;
    double parallel_distance = 0;
    double angle_deg = 0;
    bool set_xyz = false;
};

struct MeasureOutcome {
    std::vector<MeasuredFeature> features;        // one per pick that found one
    std::optional<Measurement> measurement;       // with two
    AssemblyOptions assembly;
};

MeasureOutcome measure(const Project& project, const std::vector<FeaturePick>& picks, FeatureFilter filter);

// Assembly steps: the second feature's part moves (its instance, or the part
// itself within one object), except FlipFirst, which turns the first's.
enum class AssemblyStep : int {
    Parallel = 0,           // turn so the faces face each other
    CenterCoincidence = 1,  // and put the second face's center on the first's
    FlipFirst = 2,          // turn half way about an axis in the face
    FlipSecond = 3,
    RotateAround = 4,       // `value` degrees about the second face's normal
    ParallelDistance = 5,   // `value` mm between parallel faces
    SetXyz = 6,             // second minus first becomes `xyz` (points and circles)
};

// Throws std::invalid_argument when the features cannot take the step.
void assemble(Project& project, const FeaturePick& first, const FeaturePick& second, FeatureFilter filter,
              AssemblyStep step, double value, const std::array<double, 3>& xyz);

} // namespace orca::engine
