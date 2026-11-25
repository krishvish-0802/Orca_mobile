package com.orcamobile.feature.devices

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.orcamobile.core.model.FanKind
import com.orcamobile.core.model.HeaterKind
import com.orcamobile.core.model.MachineState
import com.orcamobile.core.model.PrinterLink
import com.orcamobile.core.model.PrinterState

/** What a printer is doing, in a few words. */
@Composable
internal fun statusText(state: PrinterState): String = when (state.link) {
    PrinterLink.Connecting -> stringResource(R.string.devices_connecting)
    is PrinterLink.Offline -> stringResource(R.string.devices_offline)
    PrinterLink.Online -> stringResource(
        when (state.machine) {
            MachineState.UNKNOWN -> R.string.devices_online
            MachineState.IDLE -> R.string.devices_idle
            MachineState.PREPARING -> R.string.devices_preparing
            MachineState.PRINTING -> R.string.devices_printing
            MachineState.PAUSING -> R.string.devices_pausing
            MachineState.PAUSED -> R.string.devices_paused
            MachineState.STOPPING -> R.string.devices_stopping
            MachineState.FINISHED -> R.string.devices_finished
            MachineState.CANCELLED -> R.string.devices_cancelled
            MachineState.ERROR -> R.string.devices_error
            MachineState.BUSY -> R.string.devices_busy
        },
    )
}

@Composable
internal fun heaterName(kind: HeaterKind): String = stringResource(
    when (kind) {
        HeaterKind.NOZZLE -> R.string.devices_nozzle
        HeaterKind.BED -> R.string.devices_bed
        HeaterKind.CHAMBER -> R.string.devices_chamber
    },
)

@Composable
internal fun fanName(kind: FanKind): String = stringResource(
    when (kind) {
        FanKind.PART -> R.string.devices_fan_part
        FanKind.AUXILIARY -> R.string.devices_fan_auxiliary
        FanKind.CHAMBER -> R.string.devices_fan_chamber
    },
)

/** "1 h 23 min", "12 min", "45 s". */
internal fun duration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "$h h $m min"
        m > 0 -> "$m min"
        else -> "$seconds s"
    }
}

/** Whether a print is under way (it can be paused or stopped). */
internal val MachineState.active: Boolean
    get() = this == MachineState.PRINTING || this == MachineState.PREPARING || this == MachineState.PAUSING ||
        this == MachineState.PAUSED || this == MachineState.STOPPING
