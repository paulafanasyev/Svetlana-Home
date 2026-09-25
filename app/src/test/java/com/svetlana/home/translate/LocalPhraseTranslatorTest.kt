package com.svetlana.home.translate

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LocalPhraseTranslatorTest {

    private val translator = LocalPhraseTranslator()

    @Test
    fun `ru to vi translates known phrase`() = runBlocking {
        val result = translator.translate("Привет", TranslateDirection.RU_TO_VI)
        assertThat(result.success).isTrue()
        assertThat(result.text).isEqualTo("Xin chào")
    }

    @Test
    fun `vi to ru translates back`() = runBlocking {
        val result = translator.translate("cảm ơn", TranslateDirection.VI_TO_RU)
        assertThat(result.success).isTrue()
        assertThat(result.text).isEqualTo("Спасибо")
    }

    @Test
    fun `unknown phrase fails honestly`() = runBlocking {
        val result = translator.translate("суперкалифрагилистика", TranslateDirection.RU_TO_VI)
        assertThat(result.success).isFalse()
        assertThat(result.error).contains("Локальный переводчик")
    }

    @Test
    fun `empty text is rejected`() = runBlocking {
        val result = translator.translate("   ", TranslateDirection.RU_TO_VI)
        assertThat(result.success).isFalse()
    }

    @Test
    fun `multiword phrase partially translates`() = runBlocking {
        val result = translator.translate("Спасибо большое", TranslateDirection.RU_TO_VI)
        assertThat(result.success).isTrue()
        assertThat(result.text).isEqualTo("Cảm ơn большое")
    }
}
