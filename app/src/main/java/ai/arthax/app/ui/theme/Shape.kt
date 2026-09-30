package ai.arthax.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The CRM's radius scale.
 *
 * The web sets `--radius: 0.75rem` and derives the rest from it by a multiplier; these are
 * those multipliers resolved at a 16px root, rounded to whole dp:
 *
 *   sm  0.6x -> 7dp    md 0.8x -> 10dp   lg 1.0x -> 12dp
 *   xl  1.4x -> 17dp   2xl 1.8x -> 22dp
 *
 * Mapped onto Material's five slots so a card on the phone has the same corner as the same
 * card on the web.
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(7.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(17.dp),
    extraLarge = RoundedCornerShape(22.dp),
)
