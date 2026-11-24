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
import androidx.compose.runtime.LaunchedEffect
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
import com.orcamobile.core.engine.MeshToolsJson
import com.orcamobile.core.engine.ObjectToolsJson
import com.orcamobile.core.engine.NativePreviewRegistry
import com.orcamobile.core.engine.NativeProjectRegistry
import com.orcamobile.core.model.EmbossTarget
import com.orcamobile.core.model.WipeTower
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.BrimEar
import com.orcamobile.core.model.CutCheck
import com.orcamobile.core.model.CutPlanePoint
import com.orcamobile.core.model.CutRequest
import com.orcamobile.core.model.FeaturePick
import com.orcamobile.core.model.InstanceRef
import com.orcamobile.core.model.MeasureOutcome
import com.orcamobile.core.model.MeasureRequest
import com.orcamobile.core.model.PaintBrush
import com.orcamobile.core.model.PaintKind
import com.orcamobile.core.model.PreviewId
import com.orcamobile.core.model.ProjectId
import com.orcamobile.core.model.SimplifyCounts
import com.orcamobile.core.model.SimplifyTarget
import com.orcamobile.core.model.ToolpathColoring
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
    /**
     * Other instances selected with [selection]: drawn selected, and a drag or a
     * twist moves them with it. A long press on an object adds it to the
     * selection or takes it out ([onSelectionToggle]).
     */
    group: List<InstanceRef> = emptyList(),
    onSelectionToggle: (InstanceRef) -> Unit = {},
    /** A quick tap with two fingers (undo) or three (redo), as the desktop's shortcuts. */
    onTwoFingerTap: () -> Unit = {},
    onThreeFingerTap: () -> Unit = {},
    onPlateEdited: () -> Unit = {},
    /** Grows when the engine moved the objects without a gesture, so they are redrawn. */
    projectRevision: Int = 0,
    /** Changes when the plate shows something else (another plate): the camera frames it then, and only then. */
    frameKey: Int = 0,
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
    /**
     * Painting the selected object with this brush: one finger paints, two turn
     * and zoom the camera, and nothing moves. [onPainted] follows each stroke
     * that changed what will be sliced. [paintPalette] colours each painted state.
     */
    paint: PaintBrush? = null,
    paintPalette: List<Color> = emptyList(),
    /** With several filaments, each filament's colour (filament 1 first): parts show in theirs. */
    filamentColours: List<Color> = emptyList(),
    /** Grows to erase everything painted of the brush's kind. */
    clearPaintRequest: Int = 0,
    /** Grows when the model's paint changed under the brush (undo): painting starts again from it. */
    paintRevision: Int = 0,
    /**
     * Shows the relief a texture bake of this object would make (by index), in
     * place of its parts, worked out again whenever [texturePreviewKey] changes;
     * null shows the parts as they are.
     */
    texturePreview: Int? = null,
    texturePreviewKey: Int = 0,
    /** A texture preview is being worked out (true), or is shown (false). */
    onTexturePreviewBusy: (Boolean) -> Unit = {},
    /**
     * Fingers place the texture instead of painting: one drags it across the
     * surface ([onTextureShift], screen px), two pinch its size ([onTextureScale])
     * and twist it ([onTextureTurn], degrees clockwise on screen).
     */
    placingTexture: Boolean = false,
    onTextureShift: (Offset) -> Unit = {},
    onTextureScale: (Float) -> Unit = {},
    onTextureTurn: (Float) -> Unit = {},
    onPainted: () -> Unit = {},
    /** Painting a new painted modifier: the first stroke made it, volume [Int] of the object. */
    onPaintedModifier: (Int) -> Unit = {},
    /**
     * Cutting with this plane, connectors and options: the plate shows the
     * plane, the cut face and the connectors, the object above the plane in
     * orange and below in blue. One finger orbits, two pan and zoom, a tap on
     * the plane reports where ([onCutPlaneTap]), and a handle at the plate's
     * edge drags the plane up or down ([onCutHeightDrag], mm). [onCutChecked]
     * gets the engine's checks of each cut shown.
     */
    cut: CutRequest? = null,
    /** Hide what is above the plane so its cut face shows, for placing connectors. */
    cutShowsFace: Boolean = false,
    onCutChecked: (CutCheck) -> Unit = {},
    onCutPlaneTap: (CutPlanePoint) -> Unit = {},
    onCutHeightDrag: (Double) -> Unit = {},
    /** Previewing Simplify: the result's wireframe over the object; [onSimplifyCounted] gets its triangles. */
    simplify: SimplifyTarget? = null,
    onSimplifyCounted: (SimplifyCounts) -> Unit = {},
    /**
     * Measuring: the picked features are drawn, a tap on a part reports a new
     * pick ([onMeasurePick]), one finger orbits and two pan and zoom.
     * [onMeasured] gets what the engine found for each request shown.
     */
    measure: MeasureRequest? = null,
    onMeasurePick: (FeaturePick) -> Unit = {},
    onMeasured: (MeasureOutcome) -> Unit = {},
    /**
     * Editing brim ears of [BrimEarsView.instance]: its outline on the bed and the
     * ears are drawn; a tap reports where it lands, snapped to the outline when
     * near it ([onBrimEarTap]).
     */
    brimEars: BrimEarsView? = null,
    onBrimEarTap: (BrimEarTap) -> Unit = {},
    /** Taps report the part surface they land on ([onSurfaceTap]), for placing text. */
    pickingSurface: Boolean = false,
    onSurfaceTap: (FeaturePick) -> Unit = {},
    /**
     * A text being edited (with [pickingSurface]): one finger on it slides it
     * over the surface, two twist it on the surface, and a Size handle at the
     * plate's edge scales it ([onTextResize], a factor). [onTextMoved] follows a
     * drag or twist.
     */
    editingText: EmbossTarget? = null,
    onTextMoved: () -> Unit = {},
    onTextResize: (Double) -> Unit = {},
    /**
     * The plate's prime tower: drawn on the bed (red when it meets an object);
     * a one-finger drag on it moves it, and [onWipeTowerMoved] gets its new corner.
     */
    wipeTower: WipeTower? = null,
    onWipeTowerMoved: (x: Double, y: Double) -> Unit = { _, _ -> },
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
    val currentGroup by rememberUpdatedState(group)
    val currentOnTwoFingerTap by rememberUpdatedState(onTwoFingerTap)
    val currentOnThreeFingerTap by rememberUpdatedState(onThreeFingerTap)
    val currentOnSelectionToggle by rememberUpdatedState(onSelectionToggle)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val currentOnPlateEdited by rememberUpdatedState(onPlateEdited)
    val currentLayOnFace by rememberUpdatedState(layOnFace)
    val currentOnLaidOnFace by rememberUpdatedState(onLaidOnFace)
    val currentVolume by rememberUpdatedState(highlightedVolume.takeIf { selection != null })
    val currentOnVolumeSelectionChange by rememberUpdatedState(onVolumeSelectionChange)
    val currentPaint by rememberUpdatedState(paint)
    val currentOnPainted by rememberUpdatedState(onPainted)
    val currentOnTexturePreviewBusy by rememberUpdatedState(onTexturePreviewBusy)
    val currentOnTextureShift by rememberUpdatedState(onTextureShift)
    val currentOnTextureScale by rememberUpdatedState(onTextureScale)
    val currentOnTextureTurn by rememberUpdatedState(onTextureTurn)
    val currentOnPaintedModifier by rememberUpdatedState(onPaintedModifier)
    val painting = paint != null && project != null && preview == null && selection != null
    // A drag on the tower moves it here at once; the engine's answer then replaces it.
    var towerDrag by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(wipeTower) { towerDrag = Offset.Zero }
    val shownTower = wipeTower?.let { it.copy(x = it.x + towerDrag.x, y = it.y + towerDrag.y) }
    val currentTower by rememberUpdatedState(shownTower)
    val currentOnWipeTowerMoved by rememberUpdatedState(onWipeTowerMoved)
    val towerGrab = remember { BooleanArray(1) }
    val towerArray = remember(shownTower, preview) {
        shownTower?.takeIf { preview == null }?.let {
            floatArrayOf(
                it.x.toFloat(), it.y.toFloat(), it.width.toFloat(), it.depth.toFloat(), it.height.toFloat(),
                it.brim.toFloat(), it.rotationDegrees.toFloat(), if (it.overlapsObjects) 1f else 0f,
            )
        } ?: FloatArray(0)
    }
    val filamentArray = remember(filamentColours) {
        filamentColours.flatMap { listOf(it.red, it.green, it.blue, it.alpha) }.toFloatArray()
    }
    val paletteArray = remember(paintPalette) {
        paintPalette.flatMap { listOf(it.red, it.green, it.blue, it.alpha) }.toFloatArray()
    }
    val cutting = cut != null && project != null && preview == null
    val currentOnCutChecked by rememberUpdatedState(onCutChecked)
    val currentOnCutPlaneTap by rememberUpdatedState(onCutPlaneTap)
    val currentOnCutHeightDrag by rememberUpdatedState(onCutHeightDrag)
    var cutCenter by remember { mutableStateOf<List<Double>?>(null) }
    val currentOnSimplifyCounted by rememberUpdatedState(onSimplifyCounted)
    val simplifying = simplify.takeIf { project != null && preview == null && !cutting }
    val measuring = measure.takeIf { project != null && preview == null && !cutting && simplifying == null }
    val editingEars = brimEars.takeIf { project != null && preview == null && !cutting && simplifying == null && measuring == null }
    val currentOnBrimEarTap by rememberUpdatedState(onBrimEarTap)
    val currentOnSurfaceTap by rememberUpdatedState(onSurfaceTap)
    // The tap handler below outlives these: read them through state.
    val currentEars by rememberUpdatedState(editingEars)
    val currentMeasuring by rememberUpdatedState(measuring != null)
    val surfaceTaps = pickingSurface && project != null && preview == null && !cutting && measuring == null
    val currentOnMeasurePick by rememberUpdatedState(onMeasurePick)
    val currentOnMeasured by rememberUpdatedState(onMeasured)
    // One tool's geometry over the plate at a time. The engine sections or
    // simplifies the object for every change: off the main thread.
    LaunchedEffect(cut.takeIf { cutting }, cutShowsFace, simplifying, measuring, editingEars, project, projectRevision) {
        val request = cut.takeIf { cutting }
        when {
            request != null -> {
                val checks = withContext(Dispatchers.Default) { surface.showCut(MeshToolsJson.encode(request), cutShowsFace) }
                checks?.let(MeshToolsJson::cutCheck)?.getOrNull()?.let { check ->
                    cutCenter = check.center
                    currentOnCutChecked(check)
                }
            }
            simplifying != null -> {
                cutCenter = null
                val counts = withContext(Dispatchers.Default) {
                    surface.showSimplify(simplifying.objectIndex, MeshToolsJson.encode(simplifying.volume, simplifying.settings))
                }
                counts?.let(MeshToolsJson::simplifyCounts)?.getOrNull()?.let(currentOnSimplifyCounted)
            }
            measuring != null -> {
                cutCenter = null
                val outcome = withContext(Dispatchers.Default) { surface.showMeasure(MeshToolsJson.encode(measuring)) }
                outcome?.let(MeshToolsJson::measureOutcome)?.getOrNull()?.let(currentOnMeasured)
            }
            editingEars != null -> {
                cutCenter = null
                withContext(Dispatchers.Default) {
                    surface.showBrimEars(
                        editingEars.instance.objectIndex,
                        editingEars.instance.instanceIndex,
                        ObjectToolsJson.encodeEars(editingEars.ears),
                        editingEars.selected ?: -1,
                    )
                }
            }
            else -> {
                cutCenter = null
                withContext(Dispatchers.Default) { surface.showCut(null) }
            }
        }
    }
    LaunchedEffect(clearPaintRequest) {
        if (clearPaintRequest > 0 && surface.isPainting && surface.clearPaintAndCommit()) currentOnPainted()
    }

    Box(modifier) {
        AndroidView(
            factory = { surface },
            update = { view ->
                view.setBed(outline, bed?.maxHeight?.toFloat() ?: 0f)
                view.setFilamentColours(filamentArray)
                view.setWipeTower(towerArray)
                view.setProject(project?.let(entryPoint.projectRegistry()::handleOf) ?: 0L, projectRevision, frameKey)
                view.setPreview(preview?.let(entryPoint.previewRegistry()::handleOf) ?: 0L, layers, coloring.nativeValue)
                view.setSelection(selection.takeIf { editable })
                view.setGroup(group.takeIf { editable && selection != null }.orEmpty())
                view.setHighlightedVolume(highlightedVolume.takeIf { editable && selection != null && paint == null && !cutting })
                view.setPainting(selection?.objectIndex.takeIf { painting }, paint?.kind.takeIf { painting }, paletteArray, paint?.target, paintRevision)
                view.onReliefBusy = { currentOnTexturePreviewBusy(it) }
                view.setTexturePreview(texturePreview, texturePreviewKey)
            },
            modifier = Modifier.fillMaxSize(),
        )
        val currentText by rememberUpdatedState(editingText)
        val currentOnTextMoved by rememberUpdatedState(onTextMoved)
        // Gestures are read here, above the GL view, so they stay in Compose.
        if (measuring != null || editingEars != null || surfaceTaps) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(surface) {
                        detectTapGestures(
                            onDoubleTap = { surface.fit() },
                            onTap = { position ->
                                val ears = currentEars
                                when {
                                    ears != null -> surface.brimEarPoint(
                                        ears.instance.objectIndex, ears.instance.instanceIndex, position.x, position.y, EAR_SNAP_MM,
                                    )?.let(ObjectToolsJson::earPoint)?.let { p ->
                                        currentOnBrimEarTap(BrimEarTap(p.bedX, p.bedY, p.x, p.y))
                                    }
                                    currentMeasuring -> surface.measurePick(position.x, position.y)
                                        ?.let(MeshToolsJson::featurePick)?.let(currentOnMeasurePick)
                                    else -> surface.measurePick(position.x, position.y)
                                        ?.let(MeshToolsJson::featurePick)?.let(currentOnSurfaceTap)
                                }
                            },
                        )
                    }
                    .pointerInput(surface) {
                        plateGestures(
                            grabsSelection = { position ->
                                val text = currentText
                                text != null && surface.embossHits(text, position.x, position.y)
                            },
                            onDrag = { _, to -> currentText?.let { surface.embossDrag(it, to.x, to.y) } ?: false },
                            onTwist = { degrees ->
                                // Screen angles grow clockwise; seen from the surface that is a negative turn.
                                currentText?.let { surface.embossRotate(it, -degrees * (PI.toFloat() / 180f)) } ?: false
                            },
                            onOrbit = { delta -> surface.orbit(delta.x, delta.y) },
                            onPan = { delta -> surface.pan(delta.x, delta.y) },
                            onZoom = { factor, focus -> surface.zoom(factor, focus.x, focus.y) },
                            onEdited = { currentOnTextMoved() },
                            onFingerTap = { fingers ->
                                when (fingers) {
                                    2 -> currentOnTwoFingerTap()
                                    3 -> currentOnThreeFingerTap()
                                }
                            },
                        )
                    },
            )
        } else if (cutting) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(surface) {
                        detectTapGestures(
                            onDoubleTap = { surface.fit() },
                            onTap = { position ->
                                surface.cutPointAt(position.x, position.y)?.let(MeshToolsJson::planePoint)?.let(currentOnCutPlaneTap)
                            },
                        )
                    }
                    .pointerInput(surface) {
                        plateGestures(
                            grabsSelection = { false },
                            onDrag = { _, _ -> false },
                            onTwist = { false },
                            onOrbit = { delta -> surface.orbit(delta.x, delta.y) },
                            onPan = { delta -> surface.pan(delta.x, delta.y) },
                            onZoom = { factor, focus -> surface.zoom(factor, focus.x, focus.y) },
                            onEdited = {},
                        )
                    },
            )
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
                DragHandle("Cut", "↕", AXIS_COLORS[2], onEdited = {}) { from, to, _ ->
                    val center = cutCenter
                    val dz = center?.let { surface.verticalDrag(it, from.x, from.y, to.x, to.y) }
                    if (dz != null && dz != 0f) currentOnCutHeightDrag(dz.toDouble())
                    false
                }
            }
        } else if (painting && placingTexture) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(surface) { detectTapGestures(onDoubleTap = { surface.fit() }) }
                    .pointerInput(surface) {
                        textureGestures(
                            onShift = { currentOnTextureShift(it) },
                            onScale = { currentOnTextureScale(it) },
                            onTurn = { currentOnTextureTurn(it) },
                            onFingerTap = { fingers ->
                                when (fingers) {
                                    2 -> currentOnTwoFingerTap()
                                    3 -> currentOnThreeFingerTap()
                                }
                            },
                        )
                    },
            )
        } else if (painting) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(surface) {
                        paintGestures(
                            onPaint = { position, start ->
                                val brush = currentPaint
                                brush != null && surface.paintAt(position.x, position.y, start, brush)
                            },
                            onOrbit = { delta -> surface.orbit(delta.x, delta.y) },
                            onZoom = { factor, focus -> surface.zoom(factor, focus.x, focus.y) },
                            onFingerTap = { fingers ->
                                when (fingers) {
                                    2 -> currentOnTwoFingerTap()
                                    3 -> currentOnThreeFingerTap()
                                }
                            },
                            onStrokeEnd = {
                                if (surface.commitPaint()) currentOnPainted()
                                val brush = currentPaint
                                if (brush?.kind == PaintKind.PAINTED_MODIFIER && brush.modifier == null) {
                                    surface.paintedModifier()?.let(currentOnPaintedModifier)
                                }
                            },
                        )
                    },
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(surface) {
                        detectTapGestures(
                            onDoubleTap = { surface.fit() },
                            onLongPress = { position ->
                                if (currentEditable && !currentLayOnFace) surface.pick(position.x, position.y)?.let(currentOnSelectionToggle)
                            },
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
                                towerGrab[0] = currentEditable && currentTower?.let { tower ->
                                    surface.bedPoint(position.x, position.y)?.let { tower.holds(it) }
                                } == true
                                when {
                                    towerGrab[0] -> true
                                    !currentEditable || selected == null -> false
                                    volume != null -> surface.pickVolume(position.x, position.y)
                                        .let { it?.instance == selected && it.volume == volume }
                                    else -> surface.pick(position.x, position.y).let { it == selected || it in currentGroup }
                                }
                            },
                            onDrag = { from, to ->
                                if (towerGrab[0]) {
                                    val a = surface.bedPoint(from.x, from.y)
                                    val b = surface.bedPoint(to.x, to.y)
                                    if (a != null && b != null) towerDrag += b - a
                                    a != null && b != null
                                } else if (currentVolume != null) {
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
                            onEdited = {
                                if (towerGrab[0]) {
                                    currentTower?.let { currentOnWipeTowerMoved(it.x, it.y) }
                                } else {
                                    currentOnPlateEdited()
                                }
                            },
                            onFingerTap = { fingers ->
                                when (fingers) {
                                    2 -> currentOnTwoFingerTap()
                                    3 -> currentOnThreeFingerTap()
                                }
                            },
                        )
                    },
            )
        }
        if (editingText != null && surfaceTaps && preview == null) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
                DragHandle("Size", "⇕", MaterialTheme.colorScheme.primary, onEdited = {}) { _, _, upDp ->
                    onTextResize(exp(upDp * SCALE_PER_DP).toDouble())
                    true
                }
            }
        }
        // The handles work on one object; a multi-selection moves and turns by touch.
        if (editable && selection != null && group.isEmpty() && !painting && !cutting && measuring == null && editingEars == null && !surfaceTaps) {
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

/** How far from an object's outline a tap still places a brim ear on it, mm. */
private const val EAR_SNAP_MM = 8f

/** Brim ears shown on the plate for an instance; [selected] is highlighted. */
data class BrimEarsView(val instance: InstanceRef, val ears: List<BrimEar>, val selected: Int? = null)

/**
 * A tap while editing brim ears: where it lands on the bed ([bedX], [bedY]) and
 * the nearest point of the object's outline ([x], [y]) when it is near it.
 */
data class BrimEarTap(val bedX: Double, val bedY: Double, val x: Double?, val y: Double?)

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
 * Painting: one finger paints from where it lands ([onPaint] gets each point and
 * whether a stroke starts there; [onStrokeEnd] follows a stroke that painted);
 * once a second finger joins, the gesture moves the camera instead: dragging
 * orbits, pinching zooms.
 */
private suspend fun PointerInputScope.paintGestures(
    onPaint: (Offset, start: Boolean) -> Boolean,
    onOrbit: (Offset) -> Unit,
    onZoom: (Float, Offset) -> Unit,
    onStrokeEnd: () -> Unit,
    /** A quick tap of two or more fingers: how many. */
    onFingerTap: (Int) -> Unit = {},
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // The first dab waits for the finger to move or lift, so the first finger
        // of a two-finger gesture paints nothing.
        var started = false
        var painted = false
        var camera = false
        var fingers = 1
        var travel = 0f
        var lastUptime = down.uptimeMillis
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            fingers = maxOf(fingers, pressed.size)
            travel += event.changes.sumOf { it.positionChange().getDistance().toDouble() }.toFloat()
            lastUptime = event.changes.maxOf { it.uptimeMillis }
            if (pressed.size >= 2) camera = true
            if (camera) {
                if (pressed.size >= 2) {
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()
                    if (pan != Offset.Zero) onOrbit(pan)
                    if (zoom != 1f) onZoom(zoom, event.calculateCentroid(useCurrent = true))
                }
            } else {
                val change = pressed.firstOrNull()
                if (change != null && change.positionChange() != Offset.Zero) {
                    if (!started) {
                        painted = onPaint(down.position, true)
                        started = true
                    }
                    painted = onPaint(change.position, false) || painted
                }
            }
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        // A single tap paints a dab where it landed.
        if (!camera && !started) painted = onPaint(down.position, true)
        if (painted) onStrokeEnd()
        val quick = lastUptime - down.uptimeMillis < FINGER_TAP_MILLIS
        if (camera && fingers >= 2 && quick && travel < FINGER_TAP_TRAVEL_DP * density * fingers) onFingerTap(fingers)
    }
}

/**
 * Placing a texture: one finger shifts it, two pinch its size and twist it. The
 * camera stays put; a quick tap of two or more fingers is [onFingerTap].
 */
private suspend fun PointerInputScope.textureGestures(
    onShift: (Offset) -> Unit,
    onScale: (Float) -> Unit,
    onTurn: (Float) -> Unit,
    onFingerTap: (Int) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var fingers = 1
        var travel = 0f
        var lastUptime = down.uptimeMillis
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            fingers = maxOf(fingers, pressed.size)
            travel += event.changes.sumOf { it.positionChange().getDistance().toDouble() }.toFloat()
            lastUptime = event.changes.maxOf { it.uptimeMillis }
            if (pressed.size >= 2) {
                val zoom = event.calculateZoom()
                val turn = event.calculateRotation()
                if (zoom != 1f) onScale(zoom)
                if (turn != 0f) onTurn(turn)
            } else if (fingers == 1) {
                // One finger all along: a drag (once the second lifts, nothing until all are up).
                val change = pressed.firstOrNull()?.positionChange()
                if (change != null && change != Offset.Zero) onShift(change)
            }
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        val quick = lastUptime - down.uptimeMillis < FINGER_TAP_MILLIS
        if (fingers >= 2 && quick && travel < FINGER_TAP_TRAVEL_DP * density * fingers) onFingerTap(fingers)
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
    /** A quick tap of two or more fingers that hardly moved: how many. */
    onFingerTap: (Int) -> Unit = {},
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val dragging = grabsSelection(down.position)
        var edited = false
        var fingers = 1
        var travel = 0f
        var lastUptime = down.uptimeMillis
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            // Lifting fingers mid-gesture must not turn a pinch into an orbit or a drag.
            fingers = maxOf(fingers, pressed.size)
            travel += event.changes.sumOf { it.positionChange().getDistance().toDouble() }.toFloat()
            lastUptime = event.changes.maxOf { it.uptimeMillis }
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
        val quick = lastUptime - down.uptimeMillis < FINGER_TAP_MILLIS
        if (!edited && fingers >= 2 && quick && travel < FINGER_TAP_TRAVEL_DP * density * fingers) onFingerTap(fingers)
    }
}

/** How long and how far (per finger) a multi-finger tap may last and move. */
private const val FINGER_TAP_MILLIS = 300L
private const val FINGER_TAP_TRAVEL_DP = 12f

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ViewportEntryPoint {
    fun projectRegistry(): NativeProjectRegistry

    fun previewRegistry(): NativePreviewRegistry
}

/** Whether bed point [p] (mm) is on the tower or its brim, turned as it is. */
private fun WipeTower.holds(p: Offset): Boolean {
    val a = rotationDegrees * PI / 180
    val dx = p.x - x
    val dy = p.y - y
    val u = dx * cos(a) + dy * sin(a)
    val v = -dx * sin(a) + dy * cos(a)
    val reach = brim + TOWER_REACH_MM
    return u >= -reach && u <= width + reach && v >= -reach && v <= depth + reach
}

/** How far around the tower a finger still grabs it, mm. */
private const val TOWER_REACH_MM = 3.0
