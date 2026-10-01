package ai.arthax.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
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

    /**
     * Sun, for the light theme.
     *
     * A disc and eight rays, all drawn here rather than imported: `material-icons-core`
     * ships no weather or theme glyphs, and the extended set is a 34 MB dependency for two
     * shapes. The ray geometry was generated from one radius and one angle step so the
     * spokes are exactly even, which is the thing the eye notices when they are not.
     */
    val Sun: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArthaxSun",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                // The disc.
                moveTo(12f, 6.6f)
                arcTo(5.4f, 5.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.99f, 6.6f)
                close()
                // ray 0
                moveTo(19.20f, 12.85f)
                lineTo(22.20f, 12.85f)
                lineTo(22.20f, 11.15f)
                lineTo(19.20f, 11.15f)
                close()
                // ray 1
                moveTo(16.49f, 17.69f)
                lineTo(18.61f, 19.81f)
                lineTo(19.81f, 18.61f)
                lineTo(17.69f, 16.49f)
                close()
                // ray 2
                moveTo(11.15f, 19.20f)
                lineTo(11.15f, 22.20f)
                lineTo(12.85f, 22.20f)
                lineTo(12.85f, 19.20f)
                close()
                // ray 3
                moveTo(6.31f, 16.49f)
                lineTo(4.19f, 18.61f)
                lineTo(5.39f, 19.81f)
                lineTo(7.51f, 17.69f)
                close()
                // ray 4
                moveTo(4.80f, 11.15f)
                lineTo(1.80f, 11.15f)
                lineTo(1.80f, 12.85f)
                lineTo(4.80f, 12.85f)
                close()
                // ray 5
                moveTo(7.51f, 6.31f)
                lineTo(5.39f, 4.19f)
                lineTo(4.19f, 5.39f)
                lineTo(6.31f, 7.51f)
                close()
                // ray 6
                moveTo(12.85f, 4.80f)
                lineTo(12.85f, 1.80f)
                lineTo(11.15f, 1.80f)
                lineTo(11.15f, 4.80f)
                close()
                // ray 7
                moveTo(17.69f, 7.51f)
                lineTo(19.81f, 5.39f)
                lineTo(18.61f, 4.19f)
                lineTo(16.49f, 6.31f)
                close()
            }
        }.build()
    }

    /**
     * Moon, for the dark theme.
     *
     * One disc with a second subtracted from it: an even-odd fill turns the overlap into
     * the bite that makes a crescent, which is far less code than tracing the curve and
     * keeps both edges perfectly circular.
     */
    val Moon: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArthaxMoon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(12f, 3f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.99f, 3f)
                close()
                // The bite, offset up and right so the crescent opens towards the bottom left.
                moveTo(17.5f, 6.5f)
                arcTo(8.2f, 8.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17.49f, 6.5f)
                close()
            }
        }.build()
    }

    /**
     * Half sun, half moon: follow the phone.
     *
     * A disc split down the middle, the left half filled and the right an outline, so the
     * three theme options read as one family rather than two icons and a word.
     */
    val ThemeAuto: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArthaxThemeAuto",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // The outline, as a ring.
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(12f, 3f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.99f, 3f)
                close()
                moveTo(12f, 4.7f)
                arcTo(7.3f, 7.3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.99f, 4.7f)
                close()
            }
            // The filled left half.
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 4.7f)
                arcTo(7.3f, 7.3f, 0f, isMoreThanHalf = true, isPositiveArc = false, 11.99f, 4.7f)
                close()
            }
        }.build()
    }
}
