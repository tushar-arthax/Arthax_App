package ai.arthax.app.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * The ArthaX web CRM's palette, token for token.
 *
 * Every value below is copied from the CRM's stylesheet so a rep moving between the web app
 * and the phone sees one product. Nothing here is invented: where Material needs a colour
 * the CSS has no token for — the "container" roles — it is derived by compositing an
 * existing token over the card at a fixed alpha, and the derivation is written down.
 *
 * Names mirror the CSS variables rather than the old brand-kit names, so a change on the
 * web side can be found and applied here by searching for the same word.
 */

// ---------------- Dark: "Linear Obsidian Grade" ----------------

val DarkBackground = Color(0xFF050506)
val DarkForeground = Color(0xFFF4F5F6)
val DarkCard = Color(0xFF0A0A0C)
val DarkSecondary = Color(0xFF121215)
val DarkMuted = Color(0xFF141418)
val DarkMutedForeground = Color(0xFF8E929B)

/** `--primary` / `--neon` / `--ring`. The colour the whole dark theme is built around. */
val DarkNeon = Color(0xFF00FF55)
val DarkOnNeon = Color(0xFF041007)

val DarkCyan = Color(0xFF38BDF8)
val DarkCrimson = Color(0xFFF43F5E)
val DarkAmber = Color(0xFFFBBF24)

/** `--border` and `--input`: white at 8% and 12%, kept as alpha rather than flattened. */
val DarkBorder = Color(0x14FFFFFF)
val DarkInput = Color(0x1FFFFFFF)

// ---------------- Light: "Silicon Valley Balanced Slate" ----------------

val LightBackground = Color(0xFFFBFCFB)
val LightForeground = Color(0xFF111827)
val LightCard = Color(0xFFFFFFFF)
val LightSecondary = Color(0xFFF3F4F6)
val LightSecondaryForeground = Color(0xFF1F2937)
val LightMuted = Color(0xFFF4F5F6)
val LightMutedForeground = Color(0xFF4B5563)

/** `--primary` / `--neon` / `--ring` on light. */
val LightEmerald = Color(0xFF16A34A)
val LightOnEmerald = Color(0xFFFFFFFF)

val LightCyan = Color(0xFF0284C7)
val LightCrimson = Color(0xFFE11D48)
val LightAmber = Color(0xFFD97706)

/** `--border` and `--input`: black at 9% and 14%. */
val LightBorder = Color(0x17000000)
val LightInput = Color(0x24000000)

// ---------------- Derived containers ----------------
//
// Material3 needs a filled "container" per accent — for chips, pills and the nav indicator —
// and the CSS has no token for one. Each is the accent composited over the card at a low
// alpha, which is exactly what the web does with its `--wash-*` values, resolved to a flat
// colour here because Compose draws these as opaque surfaces.

/** `--neon` at 14% over `--card`. */
val DarkNeonContainer = Color(0xFF092C16)

/** `--crimson` at 16% over `--card`. */
val DarkCrimsonContainer = Color(0xFF2F1219)

/** `--cyan` at 16% over `--card`. */
val DarkCyanContainer = Color(0xFF112732)

/** `--amber` at 16% over `--card`. */
val DarkAmberContainer = Color(0xFF312710)

/** `--primary` at 12% over white. */
val LightEmeraldContainer = Color(0xFFE3F4E9)

/** `--crimson` at 12% over white. */
val LightCrimsonContainer = Color(0xFFFBE4E9)

/** `--cyan` at 12% over white. */
val LightCyanContainer = Color(0xFFE1F0F8)

/** `--amber` at 12% over white. */
val LightAmberContainer = Color(0xFFFAEFE1)
