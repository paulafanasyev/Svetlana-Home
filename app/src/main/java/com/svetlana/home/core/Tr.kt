package com.svetlana.home.core

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Двуязычные тексты интерфейса прямо в коде: русский — основной (и для любых
 * других языков системы, как values/), английский — когда выбран English
 * ([AppLanguage]). Тексты из ресурсов переводятся через values-en/strings.xml.
 */
object Tr {
    fun isEnglish(ctx: Context): Boolean =
        ctx.resources.configuration.locales[0].language == "en"

    fun t(ctx: Context, ru: String, en: String): String = if (isEnglish(ctx)) en else ru
}

/** Текст на текущем языке интерфейса (Compose). */
@Composable
@ReadOnlyComposable
fun tr(ru: String, en: String): String =
    if (LocalConfiguration.current.locales[0].language == "en") en else ru
