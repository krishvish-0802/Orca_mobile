package com.orcamobile.core.viewport

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.orcamobile.core.engine.EngineLibrary
import com.orcamobile.core.engine.NativeProjectRegistry
import com.orcamobile.core.model.BedShape
import com.orcamobile.core.model.ProjectId
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The 3D plate: the printer's bed and the loaded project.
 *
 * Gestures (see docs/proposal/tech-stack-and-architecture.md, section 4):
 * one finger orbits, two fingers pan, pinch zooms around the fingers, and a
 * double tap frames the model.
 */
@Composable
fun PlateViewport(
    bed: BedShape?,
    project: ProjectId?,
    modifier: Modifier = Modifier,
) {
    if (!EngineLibrary.isAvailable) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("The 3D view needs the native engine.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val context = LocalContext.current
    val registry = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, ViewportEntryPoint::class.java).projectRegistry()
    }
    val outline = remember(bed) {
        bed?.outline?.flatMap { (x, y) -> listOf(x.toFloat(), y.toFloat()) }?.toFloatArray()
    }
    val surface = remember { PlateSurfaceView(context) }

    Box(modifier) {
        AndroidView(
            factory = { surface },
            update = { view ->
                view.setBed(outline, bed?.maxHeight?.toFloat() ?: 0f)
                view.setProject(project?.let(registry::handleOf) ?: 0L)
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Gestures are read here, above the GL view, so they stay in Compose.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(surface) { detectTapGestures(onDoubleTap = { surface.fit() }) }
                .pointerInput(surface) {
                    plateGestures(
                        onOrbit = { delta -> surface.orbit(delta.x, delta.y) },
                        onPan = { delta -> surface.pan(delta.x, delta.y) },
                        onZoom = { factor, focus -> surface.zoom(factor, focus.x, focus.y) },
                    )
                },
        )
    }
}

/** One finger orbits; two or more pan and pinch-zoom. */
private suspend fun PointerInputScope.plateGestures(
    onOrbit: (Offset) -> Unit,
    onPan: (Offset) -> Unit,
    onZoom: (Float, Offset) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            when {
                pressed.size == 1 -> {
                    val delta = pressed[0].positionChange()
                    if (delta != Offset.Zero) {
                        onOrbit(delta)
                        pressed[0].consume()
                    }
                }
                pressed.size >= 2 -> {
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()
                    if (pan != Offset.Zero) onPan(pan)
                    if (zoom != 1f) onZoom(zoom, event.calculateCentroid(useCurrent = true))
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ViewportEntryPoint {
    fun projectRegistry(): NativeProjectRegistry
}
