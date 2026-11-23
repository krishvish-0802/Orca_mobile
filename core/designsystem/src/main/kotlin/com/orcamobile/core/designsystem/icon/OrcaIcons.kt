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

    /** Lay on face: a block coming down onto the bed. */
    val LayOnFace: ImageVector by lazy {
        icon("LayOnFace") {
            bar(2f, 21f, 20f)
            moveTo(7f, 11f)
            horizontalLineTo(17f)
            verticalLineTo(19f)
            horizontalLineTo(7f)
            close()
            moveTo(11f, 1f)
            horizontalLineTo(13f)
            verticalLineTo(5f)
            horizontalLineTo(16f)
            lineTo(12f, 9f)
            lineTo(8f, 5f)
            horizontalLineTo(11f)
            close()
        }
    }

    /** Paint: a brush. */
    val Paint: ImageVector by lazy {
        icon("Paint") {
            // Handle, slanting up to the right.
            moveTo(20f, 2f)
            lineTo(22f, 4f)
            lineTo(13f, 13f)
            lineTo(11f, 11f)
            close()
            // Bristles.
            moveTo(10f, 12f)
            lineTo(12f, 14f)
            curveTo(12f, 17f, 10f, 20f, 3f, 21f)
            curveTo(5f, 18f, 6f, 13f, 10f, 12f)
            close()
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

    /** Cut: a block split by a slanted plane, its top lifted away. */
    val Cut: ImageVector by lazy {
        icon("Cut") {
            // Upper piece.
            moveTo(4f, 3f)
            horizontalLineTo(20f)
            verticalLineTo(8f)
            lineTo(4f, 11f)
            close()
            // Lower piece.
            moveTo(4f, 14f)
            lineTo(20f, 11f)
            verticalLineTo(21f)
            horizontalLineTo(4f)
            close()
        }
    }

    /** Measure: a ruler with ticks. */
    val Measure: ImageVector by lazy {
        icon("Measure") {
            // A slanted ruler, its ticks cut in as notches along the top.
            moveTo(2f, 15f)
            lineTo(15f, 2f)
            lineTo(22f, 9f)
            lineTo(9f, 22f)
            close()
            for (i in 0 until 4) {
                val x = 6f + i * 3f
                val y = 11f - i * 3f
                moveTo(x, y)
                lineTo(x + 1f, y - 1f)
                lineTo(x + 3f, y + 1f)
                lineTo(x + 2f, y + 2f)
                close()
            }
        }
    }

    /** Variable layer height: bars of different thickness, stacked. */
    val Layers: ImageVector by lazy {
        icon("Layers") {
            var y = 3f
            for (h in listOf(1.5f, 2.5f, 3.5f, 2.5f, 1.5f)) {
                moveTo(4f, y)
                horizontalLineTo(20f)
                verticalLineTo(y + h)
                horizontalLineTo(4f)
                close()
                y += h + 1f
            }
        }
    }

    /** Brim ears: a square with a disc at each corner. */
    val BrimEars: ImageVector by lazy {
        icon("BrimEars") {
            square(7f, 7f, 10f)
            for ((cx, cy) in listOf(5f to 5f, 19f to 5f, 5f to 19f, 19f to 19f)) {
                moveTo(cx - 3f, cy)
                arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + 3f, y1 = cy)
                arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - 3f, y1 = cy)
                close()
            }
        }
    }

    /** Text: a capital T. */
    val Text: ImageVector by lazy {
        icon("Text") {
            moveTo(4f, 3f)
            horizontalLineTo(20f)
            verticalLineTo(7f)
            horizontalLineTo(14f)
            verticalLineTo(21f)
            horizontalLineTo(10f)
            verticalLineTo(7f)
            horizontalLineTo(4f)
            close()
        }
    }

    /** Move to plate: an arrow over a plate. */
    val ToPlate: ImageVector by lazy {
        icon("ToPlate") {
            moveTo(2f, 19f)
            horizontalLineTo(22f)
            verticalLineTo(22f)
            horizontalLineTo(2f)
            close()
            moveTo(3f, 8f)
            horizontalLineTo(13f)
            verticalLineTo(4f)
            lineTo(20f, 10f)
            lineTo(13f, 16f)
            verticalLineTo(12f)
            horizontalLineTo(3f)
            close()
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
