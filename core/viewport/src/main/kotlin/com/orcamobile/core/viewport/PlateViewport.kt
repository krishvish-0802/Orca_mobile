package com.orcamobile.core.viewport

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.orcamobile.core.engine.EngineLibrary
import com.orcamobile.core.engine.NativePreviewRegistry
import com.orcamobile.core.engine.NativeProjectRegistry
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.PreviewId
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.ToolpathColoring
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlin.math.PI
import kotlin.math.exp

/**
 * The 3D plate: the printer's bed and the loaded project, or the sliced
 * toolpaths when [preview] is set (layers [layers], colored by [coloring]).
 *
 * Gestures (see docs/proposal/tech-stack-and-architecture.md, section 4):
 * - one finger orbits the camera; two fingers pan, and pinching zooms around them;
 * - a double tap frames the model;
 * - a tap selects the object under it, or clears the selection on empty space;
 * - a finger that starts on the selected object drags it across the bed;
 * - twisting two fingers turns the selected object about the vertical axis;
 * - with [layOnFace] on, a tap on an object lays it on the side tapped instead
 *   of selecting ([onLaidOnFace] reports which);
 * - a tap on a modifier, negative volume or other see-through volume picks it
 *   ([onVolumeSelectionChange]); while one is picked, a tap on a part of the same
 *   object picks that part, and a drag, twist or the height handle moves the
 *   picked volume instead of the object.
 *
 * Editing happens in the engine directly. [onPlateEdited] reports, once per
 * gesture, that the placement changed, so earlier slicing results are stale.
 * The selection is hoisted: [selection] is shown, [onSelectionChange] asks to change it.
 */
@Composable
fun PlateViewport(
    bed: BedShape?,
    project: ProjectId?,
    modifier: Modifier = Modifier,
    preview: PreviewId? = null,
    layers: IntRange? = null,
    coloring: ToolpathColoring = ToolpathColoring.FeatureType,
    selection: InstanceRef? = null,
    onSelectionChange: (InstanceRef?) -> Unit = {},
    onPlateEdited: () -> Unit = {},
    /** Grows when the engine moved the objects without a gesture, so they are redrawn. */
    projectRevision: Int = 0,
    /** The engine is changing the plate: taps and drags only move the camera. */
    locked: Boolean = false,
    layOnFace: Boolean = false,
    onLaidOnFace: (InstanceRef) -> Unit = {},
    /** A volume of the selected object to highlight (its index in the object), for the object list. */
    highlightedVolume: Int? = null,
    /** A volume was picked on the plate (its instance and index in the object), or none. */
    onVolumeSelectionChange: (InstanceRef?, Int?) -> Unit = { _, _ -> },
    /**
     * The handles at the plate's right edge for the selection: MOVE shows a height
     * handle for a picked volume; ROTATE one per axis; SCALE one per axis and one
     * for all three. Drag a handle up or down.
     */
    handleMode: HandleMode = HandleMode.MOVE,
) {
    if (!EngineLibrary.isAvailable) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("The 3D view needs the native engine.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, ViewportEntryPoint::class.java)
    }
    val outline = remember(bed) {
        bed?.outline?.flatMap { (x, y) -> listOf(x.toFloat(), y.toFloat()) }?.toFloatArray()
    }
    val surface = remember { PlateSurfaceView(context) }
    val editable = project != null && preview == null && !locked
    val currentEditable by rememberUpdatedState(editable)
    val currentSelection by rememberUpdatedState(selection)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val currentOnPlateEdited by rememberUpdatedState(onPlateEdited)
    val currentLayOnFace by rememberUpdatedState(layOnFace)
    val currentOnLaidOnFace by rememberUpdatedState(onLaidOnFace)
    val currentVolume by rememberUpdatedState(highlightedVolume.takeIf { selection != null })
    val currentOnVolumeSelectionChange by rememberUpdatedState(onVolumeSelectionChange)

    Box(modifier) {
        AndroidView(
            factory = { surface },
            update = { view ->
                view.setBed(outline, bed?.maxHeight?.toFloat() ?: 0f)
                view.setProject(project?.let(entryPoint.projectRegistry()::handleOf) ?: 0L, projectRevision)
                view.setPreview(preview?.let(entryPoint.previewRegistry()::handleOf) ?: 0L, layers, coloring.nativeValue)
                view.setSelection(selection.takeIf { editable })
                view.setHighlightedVolume(highlightedVolume.takeIf { editable && selection != null })
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Gestures are read here, above the GL view, so they stay in Compose.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(surface) {
                    detectTapGestures(
                        onDoubleTap = { surface.fit() },
                        onTap = { position ->
                            when {
                                !currentEditable -> Unit
                                currentLayOnFace -> surface.layOnFace(position.x, position.y)?.let(currentOnLaidOnFace)
                                else -> {
                                    val hit = surface.pickVolume(position.x, position.y)
                                    val pickingVolumes = currentVolume != null && hit?.instance == currentSelection
                                    when {
                                        hit == null -> {
                                            currentOnSelectionChange(null)
                                            currentOnVolumeSelectionChange(null, null)
                                        }
                                        !hit.isPart || pickingVolumes -> {
                                            currentOnSelectionChange(hit.instance)
                                            currentOnVolumeSelectionChange(hit.instance, hit.volume)
                                        }
                                        else -> {
                                            currentOnSelectionChange(hit.instance)
                                            currentOnVolumeSelectionChange(hit.instance, null)
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
                .pointerInput(surface) {
                    plateGestures(
                        grabsSelection = { position ->
                            val selected = currentSelection
                            val volume = currentVolume
                            when {
                                !currentEditable || selected == null -> false
                                volume != null -> surface.pickVolume(position.x, position.y)
                                    .let { it?.instance == selected && it.volume == volume }
                                else -> surface.pick(position.x, position.y) == selected
                            }
                        },
                        onDrag = { from, to ->
                            if (currentVolume != null) {
                                surface.moveVolume(from.x, from.y, to.x, to.y)
                            } else {
                                surface.moveSelection(from.x, from.y, to.x, to.y)
                            }
                        },
                        onTwist = { degrees ->
                            // Screen angles grow clockwise; seen from above that is a negative turn about Z.
                            val radians = -degrees * (PI.toFloat() / 180f)
                            when {
                                !currentEditable || currentSelection == null -> false
                                currentVolume != null -> surface.rotateVolume(radians)
                                else -> surface.rotateSelection(radians)
                            }
                        },
                        onOrbit = { delta -> surface.orbit(delta.x, delta.y) },
                        onPan = { delta -> surface.pan(delta.x, delta.y) },
                        onZoom = { factor, focus -> surface.zoom(factor, focus.x, focus.y) },
                        onEdited = { currentOnPlateEdited() },
                    )
                },
        )
        if (editable && selection != null) {
            val onEdited = { currentOnPlateEdited() }
            Row(
                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (handleMode) {
                    HandleMode.MOVE -> if (highlightedVolume != null) {
                        DragHandle("Z", "↕", AXIS_COLORS[2], onEdited) { from, to, _ ->
                            surface.liftVolume(from.x, from.y, to.x, to.y)
                        }
                    }
                    HandleMode.ROTATE -> for (axis in 0..2) {
                        DragHandle(AXIS_NAMES[axis], "↻", AXIS_COLORS[axis], onEdited) { _, _, upDp ->
                            surface.rotateAbout(axis, upDp * ROTATE_RADIANS_PER_DP)
                        }
                    }
                    HandleMode.SCALE -> for (axis in -1..2) {
                        val name = if (axis < 0) "All" else AXIS_NAMES[axis]
                        val color = if (axis < 0) MaterialTheme.colorScheme.primary else AXIS_COLORS[axis]
                        DragHandle(name, "⇕", color, onEdited) { _, _, upDp ->
                            surface.scaleAlong(axis, exp(upDp * SCALE_PER_DP))
                        }
                    }
                }
            }
        }
    }
}

private val AXIS_NAMES = listOf("X", "Y", "Z")

/** The desktop's axis colours: X red, Y green, Z blue. */
private val AXIS_COLORS = listOf(Color(0xFFE53935), Color(0xFF43A047), Color(0xFF1E88E5))

/** A full turn for a drag of about 600 dp. */
private val ROTATE_RADIANS_PER_DP = (2 * PI / 600).toFloat()

/** Doubling for a drag of about 140 dp up, halving for 140 dp down. */
private const val SCALE_PER_DP = 0.005f

/**
 * A strip to drag up or down. [onDrag] gets the previous and current points
 * in the plate's coordinates (as the camera's rays are) and how far up the
 * finger went, in dp; it returns whether it changed the plate.
 */
@Composable
private fun DragHandle(
    name: String,
    symbol: String,
    color: Color,
    onEdited: () -> Unit,
    onDrag: (from: Offset, to: Offset, upDp: Float) -> Boolean,
) {
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnEdited by rememberUpdatedState(onEdited)
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current.density
    Surface(
        modifier = Modifier
            .size(width = 44.dp, height = 160.dp)
            .onGloballyPositioned { origin = it.positionInParent() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var edited = false
                    var last = down.position
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.position != last) {
                            val upDp = (last.y - change.position.y) / density
                            edited = currentOnDrag(origin + last, origin + change.position, upDp) || edited
                            last = change.position
                        }
                        change.consume()
                    } while (change.pressed)
                    if (edited) currentOnEdited()
                }
            },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        border = BorderStroke(2.dp, color),
        tonalElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "▲\n$symbol\n$name\n▼",
                style = MaterialTheme.typography.labelMedium,
                color = color,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One finger orbits, or drags the selection when it starts on it ([grabsSelection]);
 * two or more pan, pinch-zoom and twist. [onDrag] and [onTwist] return whether
 * they changed the plate; [onEdited] follows a gesture that did.
 */
private suspend fun PointerInputScope.plateGestures(
    grabsSelection: (Offset) -> Boolean,
    onDrag: (from: Offset, to: Offset) -> Boolean,
    onTwist: (degrees: Float) -> Boolean,
    onOrbit: (Offset) -> Unit,
    onPan: (Offset) -> Unit,
    onZoom: (Float, Offset) -> Unit,
    onEdited: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val dragging = grabsSelection(down.position)
        var edited = false
        var fingers = 1
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            // Lifting fingers mid-gesture must not turn a pinch into an orbit or a drag.
            fingers = maxOf(fingers, pressed.size)
            when {
                pressed.size == 1 && fingers == 1 -> {
                    val change = pressed[0]
                    val delta = change.positionChange()
                    if (delta != Offset.Zero) {
                        if (dragging) {
                            edited = onDrag(change.previousPosition, change.position) || edited
                        } else {
                            onOrbit(delta)
                        }
                        change.consume()
                    }
                }
                pressed.size >= 2 -> {
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()
                    val rotation = event.calculateRotation()
                    if (pan != Offset.Zero) onPan(pan)
                    if (zoom != 1f) onZoom(zoom, event.calculateCentroid(useCurrent = true))
                    if (rotation != 0f) edited = onTwist(rotation) || edited
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
        if (edited) onEdited()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ViewportEntryPoint {
    fun projectRegistry(): NativeProjectRegistry

    fun previewRegistry(): NativePreviewRegistry
}
