package com.codexapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Codex App-inspired dark color scheme
// Codex App-inspired modern dark color scheme
private val CodexDarkColors = darkColorScheme(
    // Primary accent — Glowing Cyber Violet
    primary = Color(0xFF8B5CF6),
    primaryContainer = Color(0xFF2E1065),
    onPrimary = Color(0xFFFFFFFF),
    onPrimaryContainer = Color(0xFFEDE9FE),

    // Secondary — Cyber Cyan
    secondary = Color(0xFF06B6D4),
    secondaryContainer = Color(0xFF083344),
    onSecondary = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFFECFEFF),

    // Tertiary — Emerald Green for active states and highlights
    tertiary = Color(0xFF10B981),
    tertiaryContainer = Color(0xFF064E3B),
    onTertiary = Color(0xFFFFFFFF),
    onTertiaryContainer = Color(0xFFD1FAE5),

    // Error
    error = Color(0xFFEF4444),
    errorContainer = Color(0xFF7F1D1D),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFFFEE2E2),

    // Surface/background hierarchy (Deep Space Obsidian & Frosted Charcoal)
    background = Color(0xFF070A0F),
    surface = Color(0xFF0E1420),
    surfaceVariant = Color(0xFF172033),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFF94A3B8),

    // Outline
    outline = Color(0xFF263354),
    outlineVariant = Color(0xFF1E293B),

    // Inverse
    inverseSurface = Color(0xFFF8FAFC),
    inverseOnSurface = Color(0xFF070A0F),
    inversePrimary = Color(0xFF2E1065),

    // Surface tints
    surfaceTint = Color(0xFF8B5CF6),
)

private val CodexTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

@Composable
fun CodexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CodexDarkColors,
        typography = CodexTypography,
        content = content
    )
}
