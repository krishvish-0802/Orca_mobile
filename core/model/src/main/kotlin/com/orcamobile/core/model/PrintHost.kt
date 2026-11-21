package com.orcamobile.core.model

/**
 * Printer host protocols the app can send G-code to. More come with milestone M4.
 *
 * @property supportsStartOptions whether the host takes [PrintStartOptions] when it starts a print.
 */
enum class PrintHostType(val displayName: String, val supportsStartOptions: Boolean = false) {
    MOONRAKER("Klipper (Moonraker)"),
    OCTOPRINT("OctoPrint"),
    /** Elegoo Centauri Carbon: HTTP upload on port 80, SDCP over WebSocket on 3030 to start. */
    ELEGOO_LINK("Elegoo (ElegooLink)", supportsStartOptions = true),
}

/** Which side of a two-sided build plate is up, as the Elegoo Centauri Carbon asks. */
enum class BuildPlateSide {
    /** Textured Build Plate (Side A). */
    TEXTURED,
    /** Smooth Build Plate (Side B). */
    SMOOTH,
}

/**
 * How the printer should start a print, for hosts that ask
 * ([PrintHostType.supportsStartOptions]); others ignore it. Defaults as desktop OrcaSlicer's.
 */
data class PrintStartOptions(
    val timeLapse: Boolean = false,
    val heatedBedLeveling: Boolean = false,
    val plate: BuildPlateSide = BuildPlateSide.TEXTURED,
)

/**
 * Where and how to reach a printer host.
 *
 * @property baseUrl e.g. `http://192.168.1.50:7125`; a bare host gets `http://`.
 * @property apiKey sent as `X-Api-Key` when set.
 */
data class PrintHostConfig(
    val type: PrintHostType,
    val baseUrl: String,
    val apiKey: String? = null,
)

/** What a successful connection test reports, e.g. "Klipper ready". */
data class PrintHostInfo(val description: String)
