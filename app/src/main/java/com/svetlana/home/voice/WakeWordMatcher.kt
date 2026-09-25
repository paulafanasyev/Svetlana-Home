package com.svetlana.home.voice

/**
 * WakeWordMatcher — логика определения слова пробуждения (ТЗ §21).
 *
 * Чистая функция, не зависящая от Android/Context — покрывается
 * unit-тестами. WakeWordEngine использует её для проверки распознанного
 * текста.
 *
 * ОГРАНИЧЕНИЕ (аудит п.12): сама схема прослушивания (polling системного
 * STT короткими окнами) — это не always-on low-power аппаратный детектор.
 * Эта функция отвечает только за сопоставление слова и не делает CLAIM,
 * что прослушивание энергоэффективное.
 */
object WakeWordMatcher {

    val wakeWords: List<String> = listOf("света", "светочка", "светлана")

    /**
     * Проверить, содержит ли фраза слово пробуждения.
     * @return команда без wake word или null, если фраза — только wake word
     *         или не содержит его вовсе.
     */
    fun matchWakeWord(phrase: String): String? {
        val low = phrase.lowercase().trim().replace("ё", "е")
        val matched = wakeWords.firstOrNull {
            low == it || low.startsWith("$it ") || low.startsWith("$it,")
        } ?: return null
        return low.removePrefix(matched).trimStart(' ', ',').ifBlank { null }
    }

    /**
     * Содержит ли фраза слово пробуждения (даже если это просто «Света»).
     */
    fun containsWakeWord(phrase: String): Boolean {
        val low = phrase.lowercase().trim().replace("ё", "е")
        return wakeWords.any { low == it || low.startsWith("$it ") || low.startsWith("$it,") }
    }
}
