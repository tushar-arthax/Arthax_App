package ai.arthax.app.ui.theme

import ai.arthax.app.R
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * League Spartan — the brand typeface.
 *
 * Shipped as the single variable font Google publishes (a `wght` axis from Thin to Black),
 * not as one file per weight: 95 KB covers every weight the UI asks for, where static cuts
 * would have been four files and more than twice the size. Each [FontWeight] below binds
 * the axis to its own value, so `FontWeight.SemiBold` really is 600 rather than the
 * synthetic emboldening the platform falls back to when a weight is missing.
 *
 * Variable-font axes need API 26, which is this app's `minSdk`.
 */
@OptIn(ExperimentalTextApi::class)
private fun leagueSpartan(weight: FontWeight) = Font(
    resId = R.font.league_spartan,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val LeagueSpartan = FontFamily(
    leagueSpartan(FontWeight.Normal),
    leagueSpartan(FontWeight.Medium),
    leagueSpartan(FontWeight.SemiBold),
    leagueSpartan(FontWeight.Bold),
)

/**
 * League Spartan runs noticeably tighter and shorter than the platform default, so the
 * sizes below are a touch larger than the scale they replace and every style carries an
 * explicit `lineHeight`. Headlines take negative tracking, which is what stops a
 * geometric face this wide from reading as stretched at display sizes.
 */
val Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = LeagueSpartan,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = LeagueSpartan,
        fontWeight = FontWeight.Bold,
        fontSize = 27.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = LeagueSpartan,
        fontWeight = FontWeight.Bold,
        fontSize = 23.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = LeagueSpartan,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = LeagueSpartan,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = LeagueSpartan,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = LeagueSpartan,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = LeagueSpartan,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = LeagueSpartan,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = LeagueSpartan,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = LeagueSpartan,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = LeagueSpartan,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * Small, wide-tracked caps for badges and section headers — the brand kit sets its own
 * labels this way, and it keeps a status chip from competing with the row it labels.
 */
val OverlineStyle = TextStyle(
    fontFamily = LeagueSpartan,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 1.sp,
)

/**
 * The OTP boxes. Monospaced rather than League Spartan on purpose: the digits must not
 * shift as they are typed, and a proportional face moves every box when a 1 is entered.
 */
val OtpDigitStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 24.sp,
    textAlign = TextAlign.Center,
)
