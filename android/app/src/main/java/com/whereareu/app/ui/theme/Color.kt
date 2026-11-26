package com.whereareu.app.ui.theme

import androidx.compose.ui.graphics.Color

// Miro design system tokens (design/DESIGN.md)
val MiroBlue = Color(0xFF5B76FE)
val MiroBluePressed = Color(0xFF2A41B6)
val MiroBlueSoft = Color(0xFFE9EDFF)
val Ink = Color(0xFF1C1C1E)
val White = Color(0xFFFFFFFF)
val SlateText = Color(0xFF555A6A)
val PlaceholderGray = Color(0xFFA5A8B5)
val BorderHairline = Color(0xFFE0E2E8)
val BorderStrong = Color(0xFFC7CAD5)
val SurfaceMuted = Color(0xFFF7F8FA)

// Pastel surfaces
val TealPastel = Color(0xFFC3FAF5)
val OrangePastel = Color(0xFFFFE6CD)
val PinkPastel = Color(0xFFFDE0F0)
val CoralPastel = Color(0xFFFFC6C6)
val RosePastel = Color(0xFFFFD8F4)

// Semantic
val SuccessGreen = Color(0xFF00B473)
val WarningAmber = Color(0xFFF79009)
val WarningSurface = Color(0xFFFFF4E5)
val DangerRed = Color(0xFFD92D20)
val DangerSurface = Color(0xFFFFE4E2)
val OfflineGray = Color(0xFF98A2B3)
val VioletAccent = Color(0xFF9B51E0)
val Scrim = Color(0x66000000)

// Member identification colors (also used by map markers / tracks)
val MemberColors = listOf(
    Color(0xFF5B76FE),
    Color(0xFF00B473),
    Color(0xFFF79009),
    Color(0xFF9B51E0),
    Color(0xFFF04438),
    Color(0xFF0BA5EC),
)

fun memberColor(index: Int): Color {
    val size = MemberColors.size
    val safe = ((index % size) + size) % size
    return MemberColors[safe]
}
