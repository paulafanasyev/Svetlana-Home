package com.svetlana.home.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ТЗ §53: автоматическое определение языка в разговорном режиме.
 *
 * Логика детектора (кириллица vs латиница) вынесена в чистую функцию,
 * не зависящую от Android — покрывается JVM-тестом без устройства.
 */
class ConversationLanguageDetectorTest {

    @Test
    fun pureRussian_detectedAsRussian() {
        assertTrue("Чисто русская фраза", detectRussian("Привет, как дела"))
        assertTrue("Длинная русская фраза",
            detectRussian("Я хотел бы заказать такси в аэропорт"))
    }

    @Test
    fun pureVietnamese_detectedAsNotRussian() {
        assertFalse("Чисто вьетнамская фраза", detectRussian("Xin chào, bạn khỏe không"))
        assertFalse("Вьетнамский вопрос", detectRussian("Cảm ơn rất nhiều"))
    }

    @Test
    fun mixedLanguage_resolvesByDominantScript() {
        // Длинная вьетнамская фраза с русским словом → вьетнамский
        assertFalse("Доминирует латиница",
            detectRussian("bạn muốn đi ăn cùng tôi Привет"))
        // Длинная русская фраза с вьетнамским словом → русский
        assertTrue("Доминирует кириллица",
            detectRussian("Привет давай закажем такси chào"))
    }

    @Test
    fun emptyText_defaultsToNotRussian() {
        // Пустая строка не должна определяться как русский —
        // иначе переводчик всегда выбрал бы RU → VI.
        assertFalse("Пустая строка — не русский", detectRussian(""))
    }

    @Test
    fun numbersOnly_notRussian() {
        assertFalse("Только цифры — не русский", detectRussian("12345"))
    }

    @Test
    fun vietnameseDiacritics_notRussian() {
        // Вьетнамские диакритики — латиница
        assertFalse("Диакритики", detectRussian("người yêu thương"))
    }

    @Test
    fun cyrillicWithNumbers_stillRussian() {
        assertTrue("Цифры не мешают определению", detectRussian("Заказ 2 билета на 5 мая"))
    }

    /**
     * Та же эвристика, что использует SvetlanaTranslator в разговорном режиме:
     * кириллических символов больше латинских → русский.
     */
    private fun detectRussian(text: String): Boolean {
        val cyrillic = text.count { it in 'А'..'я' || it == 'ё' || it == 'Ё' }
        val latin = text.count { it in 'A'..'z' }
        return cyrillic > latin
    }
}
