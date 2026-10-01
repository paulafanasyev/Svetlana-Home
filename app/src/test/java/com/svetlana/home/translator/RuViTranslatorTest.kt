package com.svetlana.home.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RuViTranslatorTest {
    @Test
    fun `RU to VI uses correct language pair`() {
        val direction = TranslatorDirection.RU_TO_VI
        assertEquals("ru", direction.sourceLanguage)
        assertEquals("vi", direction.targetLanguage)
        assertEquals("ru-RU", direction.sourceLocale.toLanguageTag())
        assertEquals("vi-VN", direction.targetLocale.toLanguageTag())
    }

    @Test
    fun `VI to RU is the exact reverse language pair`() {
        val direction = TranslatorDirection.VI_TO_RU
        assertEquals("vi", direction.sourceLanguage)
        assertEquals("ru", direction.targetLanguage)
        assertEquals("vi-VN", direction.sourceLocale.toLanguageTag())
        assertEquals("ru-RU", direction.targetLocale.toLanguageTag())
        assertNotEquals(TranslatorDirection.RU_TO_VI, direction)
    }
}
