package com.orcamobile.feature.settings

import androidx.annotation.StringRes

/** A titled group of options on the settings screen. */
data class SettingsGroup(@StringRes val title: Int, val keys: List<String>)

/**
 * The options the settings screen offers, grouped as OrcaSlicer's process,
 * filament and printer tabs group them. Labels, units, limits and choices
 * come from the engine (PrintConfigDef), so they read as on the desktop.
 */
object SettingsLayout {
    val groups: List<SettingsGroup> = listOf(
        SettingsGroup(R.string.settings_group_quality, listOf("layer_height", "initial_layer_print_height", "seam_position", "ironing_type")),
        SettingsGroup(R.string.settings_group_walls, listOf("wall_loops", "top_shell_layers", "bottom_shell_layers", "only_one_wall_top")),
        SettingsGroup(R.string.settings_group_infill, listOf("sparse_infill_density", "sparse_infill_pattern")),
        SettingsGroup(
            R.string.settings_group_speed,
            listOf("outer_wall_speed", "inner_wall_speed", "sparse_infill_speed", "top_surface_speed", "initial_layer_speed", "travel_speed"),
        ),
        SettingsGroup(
            R.string.settings_group_support,
            listOf("enable_support", "support_type", "support_threshold_angle", "support_on_build_plate_only"),
        ),
        SettingsGroup(R.string.settings_group_adhesion, listOf("brim_type", "brim_width", "skirt_loops", "raft_layers")),
        SettingsGroup(
            R.string.settings_group_filament,
            listOf(
                "nozzle_temperature_initial_layer", "nozzle_temperature", "fan_min_speed", "fan_max_speed",
                "filament_max_volumetric_speed", "filament_flow_ratio",
            ),
        ),
        SettingsGroup(
            R.string.settings_group_plate,
            listOf(
                "curr_bed_type", "textured_plate_temp_initial_layer", "textured_plate_temp",
                "hot_plate_temp_initial_layer", "hot_plate_temp",
            ),
        ),
        SettingsGroup(R.string.settings_group_special, listOf("spiral_mode")),
    )

    val keys: List<String> = groups.flatMap { it.keys }
}
