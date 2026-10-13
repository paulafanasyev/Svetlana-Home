package com.svetlana.home.core

import android.content.Context
import java.util.Locale

/**
 * Язык голоса и ответов Светланы — следует за языком интерфейса ([AppLanguage]).
 *
 * Русский интерфейс (и любой не-английский) — как раньше: распознавание и синтез ru-RU,
 * ИИ без дополнительных инструкций. English — распознавание en-US, английский голос
 * и просьба к ИИ отвечать по-английски.
 */
object VoiceLanguage {
    val RU: Locale = Locale.forLanguageTag("ru-RU")
    val EN: Locale = Locale.forLanguageTag("en-US")

    fun isEnglish(ctx: Context): Boolean = runCatching { Tr.isEnglish(ctx) }.getOrDefault(false)

    /** Язык распознавания речи и голоса по умолчанию. */
    fun locale(ctx: Context): Locale = if (isEnglish(ctx)) EN else RU

    /**
     * Голос для конкретной фразы: кириллицу всегда читаем русским голосом (даже в
     * английском интерфейсе ответ ИИ или имя контакта могут быть русскими), иначе —
     * язык интерфейса. Для русского интерфейса поведение не меняется.
     */
    fun localeForText(ctx: Context, text: String): Locale =
        if (text.any { it in '\u0400'..'\u04FF' }) RU else locale(ctx)

    /** Дополнительная системная инструкция для ИИ (null — ничего не добавляем). */
    fun replyInstruction(ctx: Context): String? =
        if (isEnglish(ctx)) "You are Svetlana, the user's phone assistant. Reply in English unless the user explicitly asks for another language. Keep answers short and natural for speech."
        else null
}
