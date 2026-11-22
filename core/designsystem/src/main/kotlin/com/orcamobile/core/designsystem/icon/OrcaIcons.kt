package com.orcamobile.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The app's own icons for plate tools that the core Material icon set lacks
 * (it is kept small on purpose), drawn on the usual 24 x 24 grid. Tinted by
 * the Icon that shows them.
 */
object OrcaIcons {
    /** Arrange: four packed squares. */
    val Arrange: ImageVector by lazy {
        icon("Arrange") {
            square(3f, 3f, 8f)
            square(13f, 3f, 8f)
            square(3f, 13f, 8f)
            square(13f, 13f, 8f)
        }
    }

    /** Auto-orient: a turning arrow. */
    val Orient: ImageVector by lazy {
        icon("Orient") {
            moveTo(20f, 12f)
            arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 4f)
            lineTo(12f, 1f)
            lineTo(16f, 5f)
            lineTo(12f, 9f)
            lineTo(12f, 6f)
            arcTo(6f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 18f, y1 = 12f)
            close()
        }
    }

    /** Copies: a square in front of another. */
    val Copies: ImageVector by lazy {
        icon("Copies") {
            moveTo(8f, 2f)
            horizontalLineTo(22f)
            verticalLineTo(16f)
            horizontalLineTo(18f)
            verticalLineTo(6f)
            horizontalLineTo(8f)
            close()
            square(2f, 8f, 14f)
        }
    }

    /** Fill bed: a grid of small squares. */
    val FillBed: ImageVector by lazy {
        icon("FillBed") {
            for (row in 0 until 3) for (column in 0 until 3) square(2f + column * 7.5f, 2f + row * 7.5f, 5f)
        }
    }

    /** Tool settings: three sliders. */
    val Tune: ImageVector by lazy {
        icon("Tune") {
            bar(3f, 5f, 18f)
            bar(3f, 11f, 18f)
            bar(3f, 17f, 18f)
            square(13f, 3f, 5f)
            square(5f, 9f, 5f)
            square(10f, 15f, 5f)
        }
    }

    private fun icon(name: String, build: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(fill = SolidColor(Color.Black), pathBuilder = build)
            .build()

    private fun PathBuilder.square(x: Float, y: Float, size: Float) {
        moveTo(x, y)
        horizontalLineTo(x + size)
        verticalLineTo(y + size)
        horizontalLineTo(x)
        close()
    }

    private fun PathBuilder.bar(x: Float, centerY: Float, width: Float) {
        moveTo(x, centerY - 1f)
        horizontalLineTo(x + width)
        verticalLineTo(centerY + 1f)
        horizontalLineTo(x)
        close()
    }
}
