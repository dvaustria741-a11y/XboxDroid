package xendroid.compose.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Xbox 360 "Blades"-style tokens for the game library grid. Kept separate from the
 * Material [xendroidTheme] color scheme (Theme.kt) since only the library tiles need this
 * heavier glass/glow treatment; the rest of the app stays on stock Material components.
 */
object BladeTile {
    // Mirrors the green brand values in Theme.kt's DarkColors so the tile glow reads as
    // the same accent as the rest of the app, not a competing color.
    val Glow = Color(0xFF6FD75F)
    val GlowBright = Color(0xFFA6F398)

    val Surface = Color(0xFF14170F)
    val SurfaceRaised = Color(0xFF1B2015)
    val Border = Color(0xFF2A2E22)

    val TextPrimary = Color(0xFFF2F6EE)
    val TextSecondary = Color(0xFFB9CCB3)

    val ScreenTint = Color(0xFF0B0C08)

    val TileCorner = 14.dp
    val TileBorderWidth = 1.dp
    val TileBorderWidthFocused = 2.dp
    val IconSize = 96.dp
}
