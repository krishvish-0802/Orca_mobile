package com.orcamobile.feature.prepare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orcamobile.core.designsystem.component.NumberField
import com.orcamobile.core.designsystem.component.PrimaryPillButton
import com.orcamobile.core.designsystem.component.TonalPillButton
import com.orcamobile.core.model.ConnectorProblem
import com.orcamobile.core.model.CutConnector
import com.orcamobile.core.model.CutConnectorShape
import com.orcamobile.core.model.CutConnectorStyle
import com.orcamobile.core.model.CutConnectorType
import com.orcamobile.core.model.CutMode
import com.orcamobile.core.model.CutOptions
import com.orcamobile.core.model.DovetailProfile
import kotlin.math.roundToInt

/**
 * The desktop's Cut tool for the selected object: the plane (height and tilt;
 * the handle at the plate's edge drags it too), planar or dovetail, what
 * becomes of each piece, and connectors placed by tapping the cut face.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CutPanel(cut: CutUiState, objectName: String, onIntent: (CutIntent) -> Unit) {
    val request = cut.request ?: return
    val check = cut.check
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.prepare_cut_title, objectName), style = MaterialTheme.typography.titleMedium, maxLines = 1)

            val modes = CutMode.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = request.mode == mode,
                        onClick = { onIntent(CutIntent.SetMode(mode)) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        icon = {},
                    ) {
                        Text(stringResource(if (mode == CutMode.PLANAR) R.string.prepare_cut_planar else R.string.prepare_cut_dovetail))
                    }
                }
            }

            // --- the plane ---
            val range = check?.heightRange
            val plane = request.plane
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.prepare_cut_height), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                NumberField(
                    value = plane.heightMm,
                    onCommit = { onIntent(CutIntent.SetHeight(it)) },
                    label = stringResource(R.string.prepare_cut_height_field),
                    suffix = "mm",
                    modifier = Modifier.weight(1f),
                )
            }
            if (range != null && range.endInclusive > range.start) {
                Slider(
                    value = plane.heightMm.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
                    onValueChange = { onIntent(CutIntent.SetHeight(((it * 100).roundToInt() / 100.0))) },
                    valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                )
            }
            AngleSlider(stringResource(R.string.prepare_cut_tilt_x), plane.tiltXDegrees) { onIntent(CutIntent.SetTilt(it, plane.tiltYDegrees)) }
            AngleSlider(stringResource(R.string.prepare_cut_tilt_y), plane.tiltYDegrees) { onIntent(CutIntent.SetTilt(plane.tiltXDegrees, it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TonalPillButton(stringResource(R.string.prepare_cut_level), onClick = { onIntent(CutIntent.ResetPlane) }, modifier = Modifier.weight(1f))
                TonalPillButton(
                    stringResource(R.string.prepare_cut_flip),
                    onClick = { onIntent(CutIntent.SetTilt(wrapDegrees(plane.tiltXDegrees + 180), plane.tiltYDegrees)) },
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider()
            when (request.mode) {
                CutMode.PLANAR -> {
                    PiecesSection(request.options, request.hasConnectors) { onIntent(CutIntent.SetOptions(it)) }
                    HorizontalDivider()
                    ConnectorsSection(cut, onIntent)
                }
                CutMode.DOVETAIL -> {
                    PiecesSection(request.options, forcedObjects = false, dovetail = true) { onIntent(CutIntent.SetOptions(it)) }
                    HorizontalDivider()
                    DovetailSection(request.dovetail, check?.dovetailDepthMm ?: 0.0) { onIntent(CutIntent.SetDovetail(it)) }
                }
            }

            val problem = cut.error ?: check?.reason?.takeIf { !check.canCut && it.isNotEmpty() }
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TonalPillButton(stringResource(R.string.prepare_cut_cancel), onClick = { onIntent(CutIntent.Close) }, modifier = Modifier.weight(1f))
                PrimaryPillButton(
                    stringResource(if (cut.cutting) R.string.prepare_cut_cutting else R.string.prepare_cut_perform),
                    onClick = { onIntent(CutIntent.Perform) },
                    enabled = cut.canCut,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AngleSlider(label: String, degrees: Double, onChange: (Double) -> Unit) {
    Text(stringResource(R.string.prepare_cut_angle, label, degrees.roundToInt()), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = degrees.toFloat().coerceIn(-180f, 180f),
        onValueChange = { onChange(it.roundToInt().toDouble()) },
        valueRange = -180f..180f,
    )
}

/** Each piece: kept or not, stood on its cut face, turned upside down; as objects or parts. */
@Composable
private fun PiecesSection(
    options: CutOptions,
    forcedObjects: Boolean,
    dovetail: Boolean = false,
    onChange: (CutOptions) -> Unit,
) {
    Text(stringResource(R.string.prepare_cut_pieces), style = MaterialTheme.typography.labelLarge)
    if (forcedObjects) {
        Text(
            stringResource(R.string.prepare_cut_connectors_keep_both),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val keepChoice = !forcedObjects && !dovetail
    PieceRow(
        title = stringResource(R.string.prepare_cut_upper),
        keep = options.keepUpper || !keepChoice,
        keepEnabled = keepChoice,
        placeOnCut = options.placeOnCutUpper,
        flip = options.flipUpper,
        onKeep = { onChange(options.copy(keepUpper = it)) },
        onPlaceOnCut = { onChange(options.copy(placeOnCutUpper = it, flipUpper = if (it) false else options.flipUpper)) },
        onFlip = { onChange(options.copy(flipUpper = it, placeOnCutUpper = if (it) false else options.placeOnCutUpper)) },
    )
    PieceRow(
        title = stringResource(R.string.prepare_cut_lower),
        keep = options.keepLower || !keepChoice,
        keepEnabled = keepChoice,
        placeOnCut = options.placeOnCutLower,
        flip = options.flipLower,
        onKeep = { onChange(options.copy(keepLower = it)) },
        onPlaceOnCut = { onChange(options.copy(placeOnCutLower = it, flipLower = if (it) false else options.flipLower)) },
        onFlip = { onChange(options.copy(flipLower = it, placeOnCutLower = if (it) false else options.placeOnCutLower)) },
    )
    if (!forcedObjects) {
        LabeledSwitch(stringResource(R.string.prepare_cut_as_parts), options.asParts) { onChange(options.copy(asParts = it)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PieceRow(
    title: String,
    keep: Boolean,
    keepEnabled: Boolean,
    placeOnCut: Boolean,
    flip: Boolean,
    onKeep: (Boolean) -> Unit,
    onPlaceOnCut: (Boolean) -> Unit,
    onFlip: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = keep, enabled = keepEnabled, onClick = { onKeep(!keep) }, label = { Text(stringResource(R.string.prepare_cut_keep)) })
            FilterChip(selected = placeOnCut, enabled = keep, onClick = { onPlaceOnCut(!placeOnCut) }, label = { Text(stringResource(R.string.prepare_cut_place_on_cut)) })
            FilterChip(selected = flip, enabled = keep, onClick = { onFlip(!flip) }, label = { Text(stringResource(R.string.prepare_cut_flip_piece)) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectorsSection(cut: CutUiState, onIntent: (CutIntent) -> Unit) {
    val request = cut.request ?: return
    val selected = cut.selectedConnector?.let { request.connectors.getOrNull(it) }
    // The fields edit the selected connector, or the next one.
    val c = selected ?: cut.connector
    val set: (CutConnector) -> Unit = { onIntent(CutIntent.SetConnector(it)) }
    Text(
        pluralStringResource(R.plurals.prepare_cut_connectors, request.connectors.size, request.connectors.size),
        style = MaterialTheme.typography.labelLarge,
    )
    LabeledSwitch(stringResource(R.string.prepare_cut_add_connectors), cut.addingConnectors) {
        onIntent(CutIntent.SetAddingConnectors(it))
    }
    Text(
        stringResource(if (cut.addingConnectors) R.string.prepare_cut_add_hint else R.string.prepare_cut_select_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val problems = cut.check?.problems.orEmpty()
    val bad = problems.count { it != ConnectorProblem.NONE }
    if (bad > 0) {
        Text(
            pluralStringResource(R.plurals.prepare_cut_bad_connectors, bad, bad),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    cut.selectedConnector?.let { index ->
        val problem = problems.getOrNull(index) ?: ConnectorProblem.NONE
        Text(
            stringResource(R.string.prepare_cut_selected_connector, index + 1) +
                if (problem == ConnectorProblem.NONE) "" else " · " + stringResource(problemLabel(problem)),
            style = MaterialTheme.typography.bodyMedium,
            color = if (problem == ConnectorProblem.NONE) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (type in CutConnectorType.entries) {
            FilterChip(
                selected = c.type == type,
                onClick = {
                    // A snap reaches at least its size deep, as on the desktop.
                    set(c.copy(type = type, depthMm = if (type == CutConnectorType.SNAP) maxOf(c.depthMm, c.sizeMm) else c.depthMm))
                },
                label = { Text(stringResource(typeLabel(type))) },
            )
        }
    }
    if (c.type != CutConnectorType.SNAP) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (style in CutConnectorStyle.entries) {
                FilterChip(selected = c.style == style, onClick = { set(c.copy(style = style)) }, label = { Text(stringResource(styleLabel(style))) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (shape in CutConnectorShape.entries) {
                FilterChip(selected = c.shape == shape, onClick = { set(c.copy(shape = shape)) }, label = { Text(stringResource(shapeLabel(shape))) })
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(c.sizeMm, { if (it > 0) set(c.copy(sizeMm = it)) }, stringResource(R.string.prepare_cut_size), Modifier.weight(1f), "mm")
        NumberField(c.depthMm, { if (it > 0) set(c.copy(depthMm = it)) }, stringResource(R.string.prepare_cut_depth), Modifier.weight(1f), "mm")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(c.sizeToleranceMm, { if (it >= 0) set(c.copy(sizeToleranceMm = it)) }, stringResource(R.string.prepare_cut_size_tolerance), Modifier.weight(1f), "mm")
        NumberField(c.depthToleranceMm, { if (it >= 0) set(c.copy(depthToleranceMm = it)) }, stringResource(R.string.prepare_cut_depth_tolerance), Modifier.weight(1f), "mm")
    }
    if (c.shape != CutConnectorShape.CIRCLE && c.type != CutConnectorType.SNAP) {
        AngleSlider(stringResource(R.string.prepare_cut_connector_rotation), c.rotationDegrees) { set(c.copy(rotationDegrees = it)) }
    }
    if (request.connectors.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TonalPillButton(
                stringResource(R.string.prepare_cut_remove_connector),
                onClick = { onIntent(CutIntent.RemoveSelectedConnector) },
                enabled = cut.selectedConnector != null,
                modifier = Modifier.weight(1f),
            )
            TonalPillButton(
                stringResource(R.string.prepare_cut_remove_all),
                onClick = { onIntent(CutIntent.RemoveAllConnectors) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DovetailSection(profile: DovetailProfile, autoDepthMm: Double, onChange: (DovetailProfile) -> Unit) {
    val depth = if (profile.depthMm > 0) profile.depthMm else autoDepthMm
    val width = if (profile.widthMm > 0) profile.widthMm else 4 * depth
    Text(stringResource(R.string.prepare_cut_dovetail_profile), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(depth, { if (it > 0) onChange(profile.copy(depthMm = it)) }, stringResource(R.string.prepare_cut_depth), Modifier.weight(1f), "mm")
        NumberField(width, { if (it > 0) onChange(profile.copy(widthMm = it)) }, stringResource(R.string.prepare_cut_width), Modifier.weight(1f), "mm")
    }
    Text(stringResource(R.string.prepare_cut_flaps_angle, profile.flapsAngleDegrees.roundToInt()), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = profile.flapsAngleDegrees.toFloat(),
        onValueChange = { onChange(profile.copy(flapsAngleDegrees = it.roundToInt().toDouble())) },
        valueRange = 30f..120f,
    )
    AngleSlider(stringResource(R.string.prepare_cut_groove_rotation), profile.angleDegrees) { onChange(profile.copy(angleDegrees = it)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(profile.depthToleranceMm, { if (it >= 0) onChange(profile.copy(depthToleranceMm = it)) }, stringResource(R.string.prepare_cut_depth_tolerance), Modifier.weight(1f), "mm")
        NumberField(profile.widthToleranceMm, { if (it >= 0) onChange(profile.copy(widthToleranceMm = it)) }, stringResource(R.string.prepare_cut_width_tolerance), Modifier.weight(1f), "mm")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(profile.count.toDouble(), { onChange(profile.copy(count = it.roundToInt().coerceIn(1, 10))) }, stringResource(R.string.prepare_cut_dovetail_count), Modifier.weight(1f), decimals = 0)
        NumberField(profile.gapMm, { if (it >= 0) onChange(profile.copy(gapMm = it)) }, stringResource(R.string.prepare_cut_dovetail_gap), Modifier.weight(1f), "mm", enabled = profile.count > 1)
    }
    Text(
        stringResource(R.string.prepare_cut_dovetail_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** An angle in -180..180. */
internal fun wrapDegrees(degrees: Double): Double {
    var d = degrees % 360.0
    if (d > 180) d -= 360
    if (d < -180) d += 360
    return d
}

private fun typeLabel(type: CutConnectorType) = when (type) {
    CutConnectorType.PLUG -> R.string.prepare_cut_type_plug
    CutConnectorType.DOWEL -> R.string.prepare_cut_type_dowel
    CutConnectorType.SNAP -> R.string.prepare_cut_type_snap
}

private fun styleLabel(style: CutConnectorStyle) = when (style) {
    CutConnectorStyle.PRISM -> R.string.prepare_cut_style_prism
    CutConnectorStyle.FRUSTUM -> R.string.prepare_cut_style_frustum
}

private fun shapeLabel(shape: CutConnectorShape) = when (shape) {
    CutConnectorShape.TRIANGLE -> R.string.prepare_cut_shape_triangle
    CutConnectorShape.SQUARE -> R.string.prepare_cut_shape_square
    CutConnectorShape.HEXAGON -> R.string.prepare_cut_shape_hexagon
    CutConnectorShape.CIRCLE -> R.string.prepare_cut_shape_circle
}

private fun problemLabel(problem: ConnectorProblem) = when (problem) {
    ConnectorProblem.OUTSIDE_SECTION -> R.string.prepare_cut_problem_outside_section
    ConnectorProblem.OVERLAPPING -> R.string.prepare_cut_problem_overlapping
    ConnectorProblem.OUTSIDE_OBJECT -> R.string.prepare_cut_problem_outside_object
    ConnectorProblem.NONE -> R.string.prepare_cut_problem_outside_section
}
