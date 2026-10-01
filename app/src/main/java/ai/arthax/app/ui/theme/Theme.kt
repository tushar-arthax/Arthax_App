package ai.arthax.app.ui.theme

import ai.arthax.app.data.local.prefs.AppSettings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Dark, mapped straight from the CRM's `.dark` block.
 *
 * `--card` is the surface and `--background` the ground behind it, which is the way round
 * the web has it: cards sit *lighter* than the page. Every `surfaceContainer*` role is set
 * explicitly rather than left to Material's tonal derivation, because that derivation
 * tints everything towards the primary — and a primary this saturated turns every menu and
 * sheet green.
 */
private val DarkColors = darkColorScheme(
    primary = DarkNeon,
    onPrimary = DarkOnNeon,
    primaryContainer = DarkNeonContainer,
    onPrimaryContainer = DarkNeon,
    inversePrimary = LightEmerald,

    secondary = DarkForeground,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSecondary,
    onSecondaryContainer = DarkForeground,

    tertiary = DarkCyan,
    onTertiary = DarkBackground,
    tertiaryContainer = DarkCyanContainer,
    onTertiaryContainer = DarkCyan,

    background = DarkBackground,
    onBackground = DarkForeground,

    surface = DarkCard,
    onSurface = DarkForeground,
    surfaceVariant = DarkMuted,
    onSurfaceVariant = DarkMutedForeground,
    surfaceTint = Color.Transparent,

    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkCard,
    surfaceContainer = DarkCard,
    surfaceContainerHigh = DarkSecondary,
    surfaceContainerHighest = DarkMuted,

    inverseSurface = DarkForeground,
    inverseOnSurface = DarkBackground,

    outline = DarkMutedForeground,
    outlineVariant = DarkBorder,

    error = DarkCrimson,
    onError = DarkBackground,
    errorContainer = DarkCrimsonContainer,
    onErrorContainer = DarkCrimson,

    scrim = Color(0xFF000000),
)

/**
 * Light, from the CRM's `:root` block.
 *
 * The emerald is darker than the neon for the same reason the web has two: `--neon` at
 * #00FF55 is 1.4:1 against white and simply cannot be seen there.
 */
private val LightColors = lightColorScheme(
    primary = LightEmerald,
    onPrimary = LightOnEmerald,
    primaryContainer = LightEmeraldContainer,
    onPrimaryContainer = Color(0xFF0A3D20),
    inversePrimary = DarkNeon,

    secondary = LightSecondaryForeground,
    onSecondary = LightCard,
    secondaryContainer = LightSecondary,
    onSecondaryContainer = LightSecondaryForeground,

    tertiary = LightCyan,
    onTertiary = LightCard,
    tertiaryContainer = LightCyanContainer,
    onTertiaryContainer = Color(0xFF0B3A55),

    background = LightBackground,
    onBackground = LightForeground,

    surface = LightCard,
    onSurface = LightForeground,
    surfaceVariant = LightMuted,
    onSurfaceVariant = LightMutedForeground,
    surfaceTint = Color.Transparent,

    surfaceContainerLowest = LightCard,
    surfaceContainerLow = LightBackground,
    surfaceContainer = LightMuted,
    surfaceContainerHigh = LightSecondary,
    surfaceContainerHighest = LightSecondary,

    inverseSurface = LightForeground,
    inverseOnSurface = LightBackground,

    outline = LightMutedForeground,
    outlineVariant = LightBorder,

    error = LightCrimson,
    onError = LightCard,
    errorContainer = LightCrimsonContainer,
    onErrorContainer = Color(0xFF6B0A22),

    scrim = Color(0xFF000000),
)

/**
 * Status colours, outside the Material scheme because "upload succeeded" and "primary" are
 * unrelated ideas.
 *
 * Success borrows the primary green rather than inventing a second one: on this palette the
 * primary *is* the success colour, and a different green beside it would read as a mistake.
 */
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
)

private val LightStatusColors = StatusColors(
    success = LightEmerald,
    onSuccess = LightCard,
    successContainer = LightEmeraldContainer,
    warning = LightAmber,
    onWarning = LightCard,
    warningContainer = LightAmberContainer,
)

private val DarkStatusColors = StatusColors(
    success = DarkNeon,
    onSuccess = DarkOnNeon,
    successContainer = DarkNeonContainer,
    warning = DarkAmber,
    onWarning = DarkBackground,
    warningContainer = DarkAmberContainer,
)

/**
 * Accents for the lead journey, one per kind of event.
 *
 * Drawn from the four accent tokens the CSS defines — neon, cyan, crimson, amber — plus the
 * foreground and muted-foreground for the two that need to read as neutral. Nothing outside
 * the stylesheet, which is why two roles share a hue: with four accents there are not six
 * distinct ones to hand out, and a colour borrowed from the sheet beats one invented for it.
 */
data class JourneyColors(
    val created: Color,
    val assigned: Color,
    val call: Color,
    val note: Color,
    val scheduled: Color,
    val lost: Color,
    val other: Color,
)

private val LightJourneyColors = JourneyColors(
    created = LightAmber,
    assigned = LightCyan,
    call = LightEmerald,
    note = LightForeground,
    scheduled = LightAmber,
    lost = LightCrimson,
    other = LightMutedForeground,
)

private val DarkJourneyColors = JourneyColors(
    created = DarkAmber,
    assigned = DarkCyan,
    call = DarkNeon,
    note = DarkForeground,
    scheduled = DarkAmber,
    lost = DarkCrimson,
    other = DarkMutedForeground,
)

val LocalJourneyColors = staticCompositionLocalOf { LightJourneyColors }

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/**
 * Resolves the rep's choice into a light or dark scheme.
 *
 * [AppSettings.ThemeMode.DARK] is the stored default, so an unconfigured install opens on
 * the look the CRM leads with whatever the phone is set to. Only
 * [AppSettings.ThemeMode.SYSTEM] defers to the handset.
 */
@Composable
fun isDarkTheme(mode: AppSettings.ThemeMode): Boolean = when (mode) {
    AppSettings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    AppSettings.ThemeMode.LIGHT -> false
    AppSettings.ThemeMode.DARK -> true
}

/**
 * No dynamic colour. Reps run this next to the web CRM on managed handsets, and the point
 * of copying that stylesheet is that both look like one product — which letting the OS
 * repaint the app from a wallpaper would undo.
 */
@Composable
fun ArthaxTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors
    val journeyColors = if (darkTheme) DarkJourneyColors else LightJourneyColors

    CompositionLocalProvider(
        LocalStatusColors provides statusColors,
        LocalJourneyColors provides journeyColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content,
        )
    }
}

/** Shorthand for the status palette at a call site. */
val statusColors: StatusColors
    @Composable get() = LocalStatusColors.current

/** Shorthand for the journey palette at a call site. */
val journeyColors: JourneyColors
    @Composable get() = LocalJourneyColors.current
