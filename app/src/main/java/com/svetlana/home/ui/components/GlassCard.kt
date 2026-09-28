package com.svetlana.home.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.svetlana.home.ui.theme.GlassDark
import com.svetlana.home.ui.theme.GlassLight
import com.svetlana.home.ui.theme.GlassLightCard
import com.svetlana.home.ui.theme.LocalSvetlanaDarkTheme
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.MintPrimaryLight
import com.svetlana.home.ui.theme.MintSoftLight

/**
 * Glass-карточка в стиле Dark Glass / Liquid Light.
 *
 * Theme-aware: в светлой теме карточка остаётся светлой с mint-рамкой,
 * в тёмной — затемнённое стекло. Цвета берутся из MaterialTheme.colorScheme,
 * поэтому единый компонент работает в обеих темах.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit
) {
    val isDark = LocalSvetlanaDarkTheme.current
    val bg = if (isDark) GlassDark else GlassLightCard
    val bgTop = if (isDark) GlassLight else Color.White.copy(alpha = 0.9f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        bgTop.copy(alpha = 0.9f),
                        bg.copy(alpha = 0.85f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        if (isDark) Color.White.copy(alpha = 0.12f) else MintPrimaryLight.copy(alpha = 0.35f),
                        if (isDark) MintPrimary.copy(alpha = 0.18f) else MintSoftLight.copy(alpha = 0.25f),
                        if (isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.6f)
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(contentPadding)
    ) { content() }
}

/**
 * Тонкая разделительная линия.
 */
@Composable
fun MintDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        MintPrimary.copy(alpha = 0.25f),
                        Color.Transparent
                    )
                )
            )
    )
}
