package com.svetlana.home.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Стиль тёмное стекло: фон почти чёрный, акценты — мягкий mint/green.
private val SvetlanaDarkScheme = darkColorScheme(
    primary = MintPrimary,
    onPrimary = Color(0xFF04120D),
    primaryContainer = GreenDeep,
    onPrimaryContainer = MintSoft,
    secondary = MintSoft,
    onSecondary = Color(0xFF04120D),
    background = AlmostBlack,
    onBackground = TextPrimary,
    surface = GlassDark,
    onSurface = TextPrimary,
    surfaceVariant = GlassLight,
    onSurfaceVariant = TextSecondary,
    tertiary = MintDimmed,
    onTertiary = TextPrimary,
    error = ErrorRed,
    onError = Color(0xFF2A0606),
    outline = DividerColor,
    outlineVariant = TextTertiary,
)

@Composable
fun SvetlanaTheme(
    // Тема всегда тёмная — это часть стиля продукта.
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SvetlanaDarkScheme,
        typography = SvetlanaTypography,
        content = content
    )
}
