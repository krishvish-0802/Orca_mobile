#pragma once

#include <array>
#include <cstddef>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace Slic3r { class Model; class ModelInstance; class DynamicPrintConfig; }

namespace orca::engine {

// One object of a loaded project, as the UI lists it.
struct ObjectSummary {
    std::string name;
    std::size_t instance_count = 0;
    std::size_t triangle_count = 0;
    std::array<double, 3> size{};     // mm, of the first instance
    std::vector<std::size_t> instance_plates;   // the plate each instance is on
};

// What a volume of an object is, as OrcaSlicer's ModelVolumeType. The values
// are shared with the viewport (orca_viewport::VolumeKind) and the app.
enum class VolumeKind : int {
    ModelPart = 0,          // printed
    NegativeVolume = 1,     // cut out of the parts
    ParameterModifier = 2,  // other settings inside it
    SupportBlocker = 3,     // no support inside it
    SupportEnforcer = 4,    // support inside it
    // Precise seam modifiers: where the seam goes inside them.
    SeamCenter = 5,
    SeamLeft = 6,
    SeamRight = 7,
    SeamEnforced = 8,
    SeamBlocked = 9,
    SeamNeutral = 10,
    PaintedModifier = 11,   // settings for an area painted on a part
};

// The desktop's "Add part / modifier / ..." generic shapes.
enum class PrimitiveShape : int { Cube = 0, Cylinder = 1, Sphere = 2, Slab = 3, Cone = 4, Torus = 5, Disc = 6 };

// Serialized setting values by key, as preset files hold them.
using SettingValues = std::map<std::string, std::string>;

struct VolumeInfo {
    std::string name;
    VolumeKind kind = VolumeKind::ModelPart;
    std::size_t triangle_count = 0;
    SettingValues settings;
    bool embossed = false;          // a text or an SVG, which the Emboss tool edits
    double painted_depth = 0;       // a painted modifier: how far it reaches in, mm (0: across the part)
};

// A height range of an object with settings of its own (layer height and
// region settings), as the desktop's "Height range modifier".
struct LayerRangeInfo {
    double min_z = 0;
    double max_z = 0;
    SettingValues settings;
};

// An object as the desktop's object list shows it.
struct ObjectDetails {
    std::string name;
    SettingValues settings;                  // per-object settings
    std::vector<VolumeInfo> volumes;         // in the object's order
    std::vector<LayerRangeInfo> layer_ranges;  // from the bottom up
};

// Where a per-object setting lives: on the object, one of its volumes, or one
// of its height ranges.
struct SettingTarget {
    std::size_t object = 0;
    std::optional<std::size_t> volume;
    std::optional<std::size_t> layer_range;
};

// Which settings a target takes, as the desktop's object, part and layer tabs:
// object = PrintObjectConfig + PrintRegionConfig; volume = PrintRegionConfig
// (and the extruder); height range = layer_height + PrintRegionConfig.
enum class SettingScope : int { Object = 0, Volume = 1, LayerRange = 2 };
std::vector<std::string> setting_keys(SettingScope scope);

// An object's mesh for drawing: every volume's triangles in that volume's own
// coordinates, model parts first, with which volume each run is and where the
// volume sits in the object (column-major 4x4).
struct ObjectMesh {
    struct Piece {
        std::size_t volume = 0;
        VolumeKind kind = VolumeKind::ModelPart;
        std::size_t first_triangle = 0;
        std::size_t triangle_count = 0;
        std::array<double, 16> transform{};
        int filament = 1;           // the filament it prints with, 1-based (its own or its object's)
    };
    std::vector<float> positions;   // x, y, z per vertex, three vertices per triangle
    std::vector<Piece> pieces;
};

// One instance of one object on the plate.
struct InstanceRef {
    std::size_t object = 0;
    std::size_t instance = 0;
    bool operator==(const InstanceRef& other) const { return object == other.object && instance == other.instance; }
};

// A build plate, as the desktop's PartPlate: the instances on it are sliced
// together; its settings (plate type, print sequence, spiral vase) override
// the slice's for it.
struct PlateSummary {
    std::string name;
    std::map<std::string, std::string> settings;
    std::size_t instance_count = 0;
};

// The keys a plate's settings take (PartPlate's own config).
std::vector<std::string> plate_setting_keys();

// Which plate each instance is on, for the bridge's plate tools. An instance
// not seen before (a new copy, a cut piece) is on the current plate.
class PlateMembership {
public:
    virtual ~PlateMembership() = default;
    virtual std::size_t current() const = 0;
    virtual std::size_t count() const = 0;
    virtual std::size_t plate_of(const Slic3r::ModelInstance& instance) = 0;
    virtual void assign(const Slic3r::ModelInstance& instance, std::size_t plate) = 0;
    // Adds an empty plate; returns its index.
    virtual std::size_t add_plate() = 0;
    // The current plate's settings.
    virtual std::map<std::string, std::string> settings() const = 0;
};

// An instance's placement: column-major 4x4, object coordinates to bed (mm).
using Placement = std::array<double, 16>;

// An instance's transform as the desktop's object manipulation panel shows it.
struct InstanceTransform {
    std::array<double, 3> position{};        // mm: the center of the object's box on the bed
    std::array<double, 3> rotation_deg{};    // Euler angles about X, Y, Z
    std::array<double, 3> scale{1, 1, 1};    // factors along the object's own axes
    std::array<double, 3> size{};            // mm: the object's own box, scaled
    std::array<bool, 3> mirrored{};
};

// A model loaded for editing and slicing: the objects of one or more imported
// files (STL, 3MF, OBJ, STEP, ...), placed on the bed. Thread-safe: the UI edits
// it while a slice may be copying it on another thread.
class Project {
public:
    // Loads `path`. Throws std::runtime_error if the file cannot be read or holds
    // no printable object.
    static std::unique_ptr<Project> load(const std::string& path);
    ~Project();
    Project(const Project&) = delete;
    Project& operator=(const Project&) = delete;

    const std::string& source_path() const;
    std::vector<ObjectSummary> objects() const;

    // Moves every instance so the objects sit together, centered on (x, y), on the bed.
    void center_on(double x, double y);

    // An object's triangles in its own coordinates, shared by its instances:
    // x, y, z per vertex, three vertices per triangle. Empty for a bad index.
    std::vector<float> object_triangles(std::size_t object) const;
    std::vector<InstanceRef> instances() const;
    // Throws std::out_of_range for a bad reference.
    Placement placement(const InstanceRef& ref) const;

    // Slides an instance across the bed.
    void translate(const InstanceRef& ref, double dx, double dy);
    // Turns an instance about the vertical axis through the center of its bounding box.
    void rotate_z(const InstanceRef& ref, double radians);
    // Turns several instances together about the vertical axis through the
    // middle of all of them, as the desktop turns a selection of objects.
    void rotate_group_z(const std::vector<InstanceRef>& refs, double radians);
    // Handles on the plate: turn about a world axis (0 X, 1 Y, 2 Z) through the
    // center of the bounding box, or grow by `factor` along one of the
    // instance's axes (-1: all three). Both leave it on the bed.
    void rotate_axis(const InstanceRef& ref, int axis, double radians);
    void scale_by(const InstanceRef& ref, int axis, double factor);

    // The object manipulation panel. Every change leaves the instance standing on
    // the bed. Throws std::out_of_range for a bad reference, std::invalid_argument
    // for a scale that is not positive or an axis other than 0..2.
    InstanceTransform transform(const InstanceRef& ref) const;
    void set_position(const InstanceRef& ref, double x, double y);
    void set_rotation(const InstanceRef& ref, const std::array<double, 3>& degrees);
    void set_scale(const InstanceRef& ref, const std::array<double, 3>& factors);
    void mirror(const InstanceRef& ref, int axis);
    // No rotation, scale 100 %, not mirrored.
    void reset_transform(const InstanceRef& ref);
    // Turns the instance so the flat side nearest `world_normal` (a face of its
    // convex hull, as the desktop's Lay on face offers) lies on the bed.
    void lay_on_face(const InstanceRef& ref, const std::array<double, 3>& world_normal);

    // Takes an instance off the plate; the object goes too when it was its last.
    // Throws std::out_of_range for a bad reference.
    void remove(const InstanceRef& ref);

    // --- An object's composition (the desktop's object list) ---
    // Throw std::out_of_range for a bad index and std::invalid_argument for a
    // change the desktop refuses too (an object's last part, a setting the
    // target does not take, a range that is empty).
    ObjectDetails details(std::size_t object) const;
    ObjectMesh object_mesh(std::size_t object) const;
    // A generic shape `size_mm` across (Slab: 1.5 x the object's footprint),
    // next to the object's front right corner, standing on the bed.
    void add_primitive(std::size_t object, VolumeKind kind, PrimitiveShape shape, double size_mm);
    // Every object in a model file, as one volume, placed like a generic shape.
    void add_volume_from_file(std::size_t object, VolumeKind kind, const std::string& path);
    void remove_volume(std::size_t object, std::size_t volume);
    // The desktop's Split to parts: a part's separate pieces become parts of
    // their own. Returns how many there are now; throws std::invalid_argument
    // when it is one piece.
    std::size_t split_to_parts(std::size_t object, std::size_t volume);
    // The desktop's Split to objects: each separate piece (each part, for an
    // object with several) becomes an object where it lies; modifiers and other
    // volumes are dropped, as there. The new objects come last; returns their
    // indices. Throws std::invalid_argument when there is nothing to split.
    std::vector<std::size_t> split_to_objects(std::size_t object);
    void set_volume_kind(std::size_t object, std::size_t volume, VolumeKind kind);
    void rename(std::size_t object, std::optional<std::size_t> volume, const std::string& name);
    // A volume's placement in its object: position (mm), rotation, scale.
    InstanceTransform volume_transform(std::size_t object, std::size_t volume) const;
    void set_volume_transform(std::size_t object, std::size_t volume, const std::array<double, 3>& position,
                              const std::array<double, 3>& rotation_deg, const std::array<double, 3>& scale);
    // A volume's placement in its object, column-major 4x4.
    std::array<double, 16> volume_matrix(std::size_t object, std::size_t volume) const;
    // Gestures on the plate, seen through instance `via`: move a volume by a
    // world distance (mm), or turn it about the vertical through its center.
    void translate_volume(const InstanceRef& via, std::size_t volume, const std::array<double, 3>& world_delta);
    void rotate_volume_z(const InstanceRef& via, std::size_t volume, double radians);
    // The same about any world axis (0 X, 1 Y, 2 Z), and growing a volume by
    // `factor` along one of its axes (-1: all three), about its center.
    void rotate_volume(const InstanceRef& via, std::size_t volume, int axis, double radians);
    void scale_volume(const InstanceRef& via, std::size_t volume, int axis, double factor);
    // Where a volume's center is on the plate, through instance `via` (mm).
    std::array<double, 3> volume_center(const InstanceRef& via, std::size_t volume) const;

    void set_setting(const SettingTarget& target, const std::string& key, const std::string& value);
    // How far a painted modifier reaches into its part from the paint, mm; 0 across the part.
    void set_painted_depth(std::size_t object, std::size_t volume, double depth_mm);
    void reset_setting(const SettingTarget& target, const std::string& key);
    // Adds a height range with its own layer height; returns its index.
    std::size_t add_layer_range(std::size_t object, double min_z, double max_z, const std::string& layer_height);
    void set_layer_range(std::size_t object, std::size_t range, double min_z, double max_z);
    void remove_layer_range(std::size_t object, std::size_t range);

    // Filaments: filament `filament` (0-based) was taken away and `count` are
    // left; objects, parts, height ranges and painting on it fall back to the
    // first, and later filaments move down. limit_filaments() drops any filament
    // number beyond `count` (a printer with fewer filaments).
    void remove_filament(std::size_t filament, std::size_t count);
    void limit_filaments(std::size_t count);

    // --- Undo and redo ---
    // record_history() keeps the project as it is now as the latest step (after
    // a change, and once after loading); undo() goes back a step and redo()
    // forward again, both returning false when there is none. A change recorded
    // after an undo drops the steps that were ahead of it. At most
    // `kHistorySteps` are kept.
    static constexpr std::size_t kHistorySteps = 30;
    void record_history();
    bool undo();
    bool redo();
    bool can_undo() const;
    bool can_redo() const;

    // --- Plates (the desktop's PartPlateList) ---
    // Every plate shares the bed's coordinates; the plate view shows the current
    // one, and plate tools and slicing work on it. Throw std::out_of_range for a
    // bad index and std::invalid_argument for a change that is refused.
    std::vector<PlateSummary> plates() const;
    std::size_t current_plate() const;
    void select_plate(std::size_t plate);
    // Adds an empty plate at the end; returns its index.
    std::size_t add_plate();
    // Takes a plate off with everything on it, as the desktop's Delete plate.
    // The last plate cannot go.
    void remove_plate(std::size_t plate);
    void rename_plate(std::size_t plate, const std::string& name);
    void set_plate_setting(std::size_t plate, const std::string& key, const std::string& value);
    void reset_plate_setting(std::size_t plate, const std::string& key);
    std::size_t plate_of(const InstanceRef& ref) const;
    // Puts an instance on another plate, where it lies.
    void move_to_plate(const InstanceRef& ref, std::size_t plate);
    // The instances on the current plate.
    std::vector<InstanceRef> plate_instances() const;
    // The current plate's settings.
    std::map<std::string, std::string> plate_settings() const;
    // A snapshot of the current plate's objects only (with only the instances on
    // it), for slicing it.
    Slic3r::Model plate_model_copy() const;
    // Runs `change` on the model and its plates under the project's lock.
    void edit_plates(const std::function<void(Slic3r::Model&, PlateMembership&)>& change);

    // Saves the project as the desktop's project file (.3mf) at `path`: its
    // objects with their parts, settings and paint, its plates (laid out apart,
    // as the desktop has them) with their names and settings, and `config`, the
    // full settings (project_settings.config). Throws std::runtime_error when
    // the file can't be written.
    void save(const std::string& path, const Slic3r::DynamicPrintConfig& config) const;
    // The settings the project file was saved with (a desktop .3mf's), into
    // `out`; false when it had none.
    bool loaded_settings(Slic3r::DynamicPrintConfig& out) const;

    // A snapshot of the model, for the slicing pipeline inside the bridge.
    Slic3r::Model model_copy() const;
    // Runs `change` on the model under the project's lock, for the bridge's plate
    // tools (see PlateTools.hpp).
    void edit(const std::function<void(Slic3r::Model&)>& change);
    // Runs `look` on the model under the project's lock, without changing it.
    void inspect(const std::function<void(const Slic3r::Model&)>& look) const;

    // The state behind it, defined in the bridge's sources only.
    struct Impl;

private:
    Project();
    std::unique_ptr<Impl> m_impl;
};

} // namespace orca::engine
