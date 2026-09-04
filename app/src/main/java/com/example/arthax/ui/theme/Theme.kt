package com.example.arthax.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Navy30,
    onPrimary = Color.White,
    primaryContainer = Sky90,
    onPrimaryContainer = Navy10,
    secondary = Sky40,
    onSecondary = Color.White,
    secondaryContainer = Sky90,
    onSecondaryContainer = Navy20,
    background = Slate98,
    onBackground = Slate10,
    surface = Color.White,
    onSurface = Slate10,
    surfaceVariant = Slate95,
    onSurfaceVariant = Slate30,
    outline = Slate50,
    outlineVariant = Slate90,
    error = Error40,
    onError = Color.White,
    errorContainer = Error90,
    onErrorContainer = Error40,
)

private val DarkColors = darkColorScheme(
    primary = Navy80,
    onPrimary = Navy10,
    primaryContainer = Navy30,
    onPrimaryContainer = Navy90,
    secondary = Sky80,
    onSecondary = Navy10,
    secondaryContainer = Navy40,
    onSecondaryContainer = Sky90,
    background = Navy10,
    onBackground = Slate90,
    surface = Navy20,
    onSurface = Slate90,
    surfaceVariant = Navy30,
    onSurfaceVariant = Slate90,
    outline = Slate50,
    outlineVariant = Navy40,
    error = Error80,
    onError = Slate10,
    errorContainer = Error40,
    onErrorContainer = Error90,
)

/**
 * Status colours live outside the Material scheme because "upload succeeded" and
 * "primary" are unrelated concepts, and folding them together makes both harder to change.
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
    success = Success40,
    onSuccess = Color.White,
    successContainer = Success90,
    warning = Warning40,
    onWarning = Color.White,
    warningContainer = Warning90,
)

private val DarkStatusColors = StatusColors(
    success = Success80,
    onSuccess = Slate10,
    successContainer = Success40,
    warning = Warning80,
    onWarning = Slate10,
    warningContainer = Warning40,
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/**
 * No dynamic colour. Reps run this next to a CRM on managed handsets, and a support
 * screenshot should look the same regardless of whose wallpaper generated the palette.
 */
@Composable
fun ArthaxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors

    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}

/** Shorthand for the status palette at a call site. */
val statusColors: StatusColors
    @Composable get() = LocalStatusColors.current
