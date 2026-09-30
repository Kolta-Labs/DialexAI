package com.dialex.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/** Semantic colors beyond MaterialTheme's roles (agent-side accents, chat bubbles, gradient brushes). */
data class AppPalette(
    val bg: Color,
    val panel: Color,
    val panelAlt: Color,
    val border: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentGradientStart: Color,
    val accentGradientEnd: Color,
    val accentGradient: Brush,
    /** Host / Left Agent. */
    val agentLeft: Color,
    val bubbleLeft: Color,
    /** Agent 2. */
    val agentRight: Color,
    val bubbleRight: Color,
    /** Agent 3. */
    val agentThird: Color,
    val bubbleThird: Color,
    /** Agent 4 (Custom/Gold). */
    val agentFourth: Color,
    val bubbleFourth: Color,
    /** Agent 5. */
    val agentFifth: Color,
    val bubbleFifth: Color,
    /** Agent 6. */
    val agentSixth: Color,
    val bubbleSixth: Color,
    /** Agent 7. */
    val agentSeventh: Color,
    val bubbleSeventh: Color,
) {
    val isDark: Boolean get() = textPrimary.luminance() > 0.5f
}

typealias CcPalette = AppPalette

fun com.dialex.model.Provider.accentColor(cc: CcPalette): Color = when (this) {
    com.dialex.model.Provider.ANTHROPIC -> cc.agentLeft
    com.dialex.model.Provider.OPENAI -> cc.agentThird
    com.dialex.model.Provider.GEMINI -> cc.agentRight
    com.dialex.model.Provider.GROK -> cc.agentFifth
    com.dialex.model.Provider.DEEPSEEK -> cc.agentSixth
    com.dialex.model.Provider.MISTRAL -> cc.agentSeventh
    com.dialex.model.Provider.OLLAMA -> Color(0xFF0EA5E9)
    com.dialex.model.Provider.CUSTOM -> cc.agentFourth
}

/** Returns a distinct, deterministic accent color per deliberation member/seat index. */
fun memberColorFor(index: Int, cc: CcPalette): Color {
    val palette = listOf(
        cc.agentLeft,    // Member 1 / Host (Saffron / Warm Terracotta)
        cc.agentRight,   // Member 2 (Sky Blue)
        cc.agentThird,   // Member 3 (Sage Green)
        cc.agentFourth,  // Member 4 (Amber / Gold)
        cc.agentFifth,   // Member 5 (Coral / Rose)
        cc.agentSixth,   // Member 6 (Electric Cyan)
        cc.agentSeventh, // Member 7 (Cosmic Violet)
        Color(0xFFEC4899), // Member 8 (Hot Pink)
        Color(0xFF14B8A6), // Member 9 (Teal)
        Color(0xFFF97316), // Member 10 (Orange)
        Color(0xFF8B5CF6), // Member 11 (Purple)
        Color(0xFF06B6D4), // Member 12 (Deep Cyan)
    )
    return palette[kotlin.math.abs(index) % palette.size]
}

// Material Saffron & Warm Terracotta Palette for Claude Debaters
val SaffronPrimary = Color(0xFFD97757)       // Saffron / Warm Terracotta
val SaffronLight = Color(0xFFE89274)         // Bright Saffron for dark surfaces
val SaffronDark = Color(0xFFB85939)          // Deep Saffron for high contrast light surfaces
val SaffronContainerDark = Color(0xFF332019) // Subtle warm saffron container for dark theme
val SaffronContainerLight = Color(0xFFFBF0EB)// Warm saffron tint for light theme

// Premium Electric Iris & Cosmic Violet Gradient (Raycast/Linear signature luxury palette)
val DarkAccentGradient = Brush.horizontalGradient(
    listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFA855F7))
)

val LightAccentGradient = Brush.horizontalGradient(
    listOf(Color(0xFF4F46E5), Color(0xFF6366F1), Color(0xFF8B5CF6))
)

val DarkPalette = AppPalette(
    bg = Color(0xFF111115),                  // Deep slate obsidian
    panel = Color(0xFF18181E),               // Clean elevated dark surface
    panelAlt = Color(0xFF22222A),            // Subtle container / control background
    border = Color(0xFF2E2E38),              // Subtle crisp border
    textPrimary = Color(0xFFF9FAFB),         // Luminous crisp off-white text
    textMuted = Color(0xFF9CA3AF),           // Clean neutral silver secondary text
    accent = Color(0xFF8B5CF6),              // Vibrant Electric Iris / Violet
    accentGradientStart = Color(0xFF6366F1), // Electric Indigo
    accentGradientEnd = Color(0xFFA855F7),   // Luminous Purple
    accentGradient = DarkAccentGradient,     // Signature horizontal gradient for CTAs and switches
    agentLeft = SaffronLight,                // Signature Claude Saffron
    bubbleLeft = SaffronContainerDark,       // Warm saffron bubble
    agentRight = Color(0xFF8AB4F8),          // Sky Blue
    bubbleRight = Color(0xFF1B2433),
    agentThird = Color(0xFF70C995),          // Sage Green
    bubbleThird = Color(0xFF1A2920),
    agentFourth = Color(0xFFF2C94C),         // Saffron Gold
    bubbleFourth = Color(0xFF332B14),
    agentFifth = Color(0xFFFF8577),          // Coral
    bubbleFifth = Color(0xFF331E1B),
    agentSixth = Color(0xFF64D2C1),          // Teal
    bubbleSixth = Color(0xFF172B27),
    agentSeventh = Color(0xFFE5A663),        // Amber
    bubbleSeventh = Color(0xFF332516),
)

val LightPalette = AppPalette(
    bg = Color(0xFFF9FAFB),                  // Crisp off-white neutral background
    panel = Color(0xFFFFFFFF),               // Clean white card / panel surface
    panelAlt = Color(0xFFF3F4F6),            // Subtle neutral container / control fill
    border = Color(0xFFE5E7EB),              // Subtle crisp border
    textPrimary = Color(0xFF111827),         // High-contrast deep dark ink text
    textMuted = Color(0xFF6B7280),           // Neutral slate/graphite secondary text
    accent = Color(0xFF6366F1),              // Electric Indigo primary
    accentGradientStart = Color(0xFF4F46E5), // Deep Indigo
    accentGradientEnd = Color(0xFF8B5CF6),   // Rich Violet
    accentGradient = LightAccentGradient,    // Signature horizontal gradient for CTAs and switches
    agentLeft = SaffronDark,                 // Saffron Dark
    bubbleLeft = SaffronContainerLight,      // Soft saffron tinted bubble
    agentRight = Color(0xFF1A73E8),          // Blue
    bubbleRight = Color(0xFFEEF4FD),
    agentThird = Color(0xFF1E7E45),          // Green
    bubbleThird = Color(0xFFEAF5EE),
    agentFourth = Color(0xFF8A6A00),         // Gold
    bubbleFourth = Color(0xFFFAF4E1),
    agentFifth = Color(0xFFC53929),          // Coral
    bubbleFifth = Color(0xFFFCEEEB),
    agentSixth = Color(0xFF007A6C),          // Teal
    bubbleSixth = Color(0xFFE6F5F2),
    agentSeventh = Color(0xFF9E5C00),        // Amber
    bubbleSeventh = Color(0xFFFAEEE0),
)

val LocalAppColors = staticCompositionLocalOf { DarkPalette }
val LocalCcColors = LocalAppColors

/** User-facing theme choice, exposed in Settings → Appearance. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private fun typographyFor(palette: AppPalette): Typography {
    val sans = FontFamily.SansSerif
    val mono = FontFamily.Monospace
    return Typography(
        headlineSmall = TextStyle(fontFamily = sans, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = palette.textPrimary),
        titleLarge = TextStyle(fontFamily = sans, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = palette.textPrimary),
        titleMedium = TextStyle(fontFamily = sans, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = palette.textPrimary),
        titleSmall = TextStyle(fontFamily = sans, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = palette.textPrimary),
        bodyLarge = TextStyle(fontFamily = sans, fontSize = 14.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, color = palette.textPrimary),
        bodyMedium = TextStyle(fontFamily = sans, fontSize = 13.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, color = palette.textPrimary),
        bodySmall = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, color = palette.textMuted),
        labelLarge = TextStyle(fontFamily = sans, fontSize = 13.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = palette.textPrimary),
        labelMedium = TextStyle(fontFamily = sans, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = palette.textPrimary),
        labelSmall = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, color = palette.textMuted),
    )
}

/** The single theme root for the whole application. */
@Composable
fun AppTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = if (dark) DarkPalette else LightPalette
    val materialScheme = if (dark) {
        darkColorScheme(
            background = palette.bg,
            surface = palette.panel,
            surfaceVariant = palette.panelAlt,
            primary = palette.accent,
            onPrimary = Color.White,
            primaryContainer = palette.accent.copy(alpha = 0.15f),
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            outline = palette.border,
            inverseSurface = Color(0xFF22222C),
            inverseOnSurface = Color(0xFFF9FAFB),
            inversePrimary = palette.accent,
        )
    } else {
        lightColorScheme(
            background = palette.bg,
            surface = palette.panel,
            surfaceVariant = palette.panelAlt,
            primary = palette.accent,
            onPrimary = Color.White,
            primaryContainer = palette.accent.copy(alpha = 0.12f),
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            outline = palette.border,
            inverseSurface = Color(0xFF1E293B),
            inverseOnSurface = Color(0xFFF9FAFB),
            inversePrimary = palette.accent,
        )
    }

    CompositionLocalProvider(LocalAppColors provides palette) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = typographyFor(palette),
            content = content,
        )
    }
}

/** Backwards-compatibility alias for mobile/legacy theme invocation. */
@Composable
fun ClaudeCodeTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) = AppTheme(mode, content)

