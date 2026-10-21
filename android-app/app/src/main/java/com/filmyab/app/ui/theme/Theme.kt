package com.filmyab.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFF5B544)
val OnAccent = Color(0xFF1A1408)
val SecondaryWarm = Color(0xFFE8DCC8)
val BgDark = Color(0xFF0C0A08)
val OnBg = Color(0xFFEDE7DC)
val SurfaceDark = Color(0xFF171310)
val OnSurfaceDark = Color(0xFFEDE7DC)
val SurfaceVariantDark = Color(0xFF221C15)
val OnSurfaceVariantDark = Color(0xFFC9BFAF)
val ErrorRed = Color(0xFFEF6C5E)
val OutlineDark = Color(0xFF3A322A)
val NavIndicator = Color(0xFF2A2118)
val DimText = Color(0xFF8A8073)
val StatusSuccess = Color(0xFF7BC47F)
val StatusLow = Color(0xFFE5B567)
val StatusFail = Color(0xFFEF6C5E)

private val FilmYabColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    secondary = SecondaryWarm,
    onSecondary = OnAccent,
    background = BgDark,
    onBackground = OnBg,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    error = ErrorRed,
    onError = Color(0xFF2B0E0A),
    outline = OutlineDark
)

@Composable
fun FilmYabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FilmYabColorScheme,
        content = content
    )
}
