#pragma once

// Helpers the mesh tools share (CutTool.cpp, MeshEdits.cpp).
#include <cmath>
#include <stdexcept>

#include "libslic3r/Model.hpp"

namespace orca::engine::detail {

// Every instance of `object` back onto the bed, as ModelObject::ensure_on_bed
// does for the one the desktop edits.
inline void settle_on_bed(Slic3r::ModelObject& object)
{
    object.invalidate_bounding_box();
    for (std::size_t k = 0; k < object.instances.size(); ++k) {
        const double min_z = object.instance_bounding_box(k).min.z();
        if (std::isfinite(min_z) && min_z != 0.0)
            object.translate_instance(k, Slic3r::Vec3d(0, 0, -min_z));
    }
    object.invalidate_bounding_box();
}

inline Slic3r::ModelObject& object_at(Slic3r::Model& model, std::size_t index)
{
    if (index >= model.objects.size())
        throw std::out_of_range("No such object on the plate");
    return *model.objects[index];
}

inline const Slic3r::ModelObject& object_at(const Slic3r::Model& model, std::size_t index)
{
    if (index >= model.objects.size())
        throw std::out_of_range("No such object on the plate");
    return *model.objects[index];
}

inline Slic3r::ModelVolume& volume_at(const Slic3r::ModelObject& object, std::size_t index)
{
    if (index >= object.volumes.size())
        throw std::out_of_range("No such part in the object");
    return *object.volumes[index];
}

} // namespace orca::engine::detail
