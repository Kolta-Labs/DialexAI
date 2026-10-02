package com.kritix.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDark = Color(0xFF0F1117)
val SurfaceDark = Color(0xFF161922)
val SurfaceCard = Color(0xFF1E2230)
val BorderDark = Color(0xFF2E344A)

val AccentCyan = Color(0xFF00F5D4)
val AccentPurple = Color(0xFF9D4EDD)
val AccentAmber = Color(0xFFFFB703)
val AccentRed = Color(0xFFFF4D6D)
val AccentGreen = Color(0xFF52B788)

val TextPrimary = Color(0xFFF8F9FA)
val TextSecondary = Color(0xFFA0AABF)
val TextMuted = Color(0xFF6C757D)

private val DarkColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = Color.Black,
    secondary = AccentPurple,
    onSecondary = Color.White,
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceCard,
    onSurfaceVariant = TextSecondary,
    error = AccentRed,
    onError = Color.White
)

@Composable
fun KritixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
