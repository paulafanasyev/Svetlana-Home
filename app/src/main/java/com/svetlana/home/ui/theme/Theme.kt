package com.svetlana.home.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

/**
 * Фактическая тёмная/светлая тема, выбранная пользователем (а не системная).
 * Компоненты вроде GlassCard и LivingOrb читают её вместо
 * isSystemInDarkTheme(), чтобы при «система тёмная, выбрана светлая»
 * не отрисовывать тёмное стекло на светлом фоне.
 */
val LocalSvetlanaDarkTheme = compositionLocalOf { true }

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

// Светлая тема: тёплый светлый фон, глубокий mint-акцент (ТЗ §6 —
// Premium AI, но не «только тёмный»: пользователь выбрал светлую тему
// с логотипом).
private val SvetlanaLightScheme = lightColorScheme(
    primary = MintPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = GreenDeepLight,
    onPrimaryContainer = AlmostBlackLight,
    secondary = MintSoftLight,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    tertiary = MintSoftLight,
    onTertiary = Color.White,
    error = ErrorRedLight,
    onError = Color.White,
    outline = LightDivider,
    outlineVariant = LightTextTertiary,
)

@Composable
fun SvetlanaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalSvetlanaDarkTheme provides darkTheme
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) SvetlanaDarkScheme else SvetlanaLightScheme,
            typography = SvetlanaTypography,
            content = content
        )
    }
}

/**
 * Тема, которая читает выбор пользователя из настроек.
 *
 * 0 — по системе, 1 — тёмная, 2 — светлая.
 * Используется во всех Activity, чтобы тема была единой.
 */
@Composable
fun SvetlanaSettingsTheme(content: @Composable () -> Unit) {
    val mode by com.svetlana.home.core.ServiceLocator.settings.themeMode
        .collectAsState(initial = 0)
    val dark = when (mode) {
        1 -> true
        2 -> false
        else -> isSystemInDarkTheme()
    }
    SvetlanaTheme(darkTheme = dark, content = content)
}
