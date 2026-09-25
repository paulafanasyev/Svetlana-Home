package com.svetlana.home.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val SvetlanaTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Light, fontSize = 40.sp, lineHeight = 48.sp, color = TextPrimary),
    displayMedium = TextStyle(fontWeight = FontWeight.Light, fontSize = 30.sp, lineHeight = 38.sp, color = TextPrimary),
    headlineMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp, color = TextPrimary),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, color = TextPrimary),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, color = TextPrimary),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp, color = TextSecondary),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, color = TextPrimary),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, color = TextSecondary),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, color = TextTertiary),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, color = MintPrimary),
    labelSmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp, color = TextTertiary),
)
