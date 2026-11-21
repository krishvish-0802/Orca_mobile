package com.orcamobile.core.model

/** Printer host protocols the app can send G-code to. More come with milestone M4. */
enum class PrintHostType(val displayName: String) {
    MOONRAKER("Klipper (Moonraker)"),
    OCTOPRINT("OctoPrint"),
}

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
