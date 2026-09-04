package com.example.arthax.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The handful of icons this app needs that are not in `material-icons-core`.
 *
 * Defined by hand rather than pulling in `material-icons-extended`, which ships roughly two
 * thousand icons as individual ImageVector getters — a 34 MB dependency that dominated the
 * APK. Everything else in the UI maps onto a core icon; only this one had no reasonable
 * equivalent.
 */
object ArthaxIcons {

    /** Standard Material "folder", used for the recordings-folder step. */
    val Folder: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArthaxFolder",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 4f)
                horizontalLineTo(4f)
                curveTo(2.89f, 4f, 2.01f, 4.89f, 2.01f, 6f)
                lineTo(2f, 18f)
                curveTo(2f, 19.11f, 2.89f, 20f, 4f, 20f)
                horizontalLineToRelative(16f)
                curveTo(21.11f, 20f, 22f, 19.11f, 22f, 18f)
                verticalLineTo(8f)
                curveTo(22f, 6.89f, 21.11f, 6f, 20f, 6f)
                horizontalLineToRelative(-8f)
                lineToRelative(-2f, -2f)
                close()
            }
        }.build()
    }
}
