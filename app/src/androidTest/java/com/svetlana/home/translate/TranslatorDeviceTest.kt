package com.svetlana.home.translate

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.core.ServiceLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ТЗ §52, §75: переводчик RU ↔ VI.
 *
 * Доказывает, что:
 *  - локальный провайдер перевода реально работает на устройстве;
 *  - RU → VI и VI → RU оба направления дают результат;
 *  - SvetlanaTranslator сообщает фактический backend в результате.
 */
@RunWith(AndroidJUnit4::class)
class TranslatorDeviceTest {

    @Before
    fun setUp() {
        ServiceLocator.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
    }

    @Test
    fun localPhraseTranslator_isAvailableOffline() {
        val provider = LocalPhraseTranslator()
        assertTrue("Локальный переводчик должен быть доступен без сети", provider.isAvailable())
    }

    @Test
    fun translateRuToVi_returnsVietnamese() = runBlocking {
        val provider = LocalPhraseTranslator()
        val result = provider.translate("привет", TranslateDirection.RU_TO_VI)

        assertTrue("RU → VI должен завершиться успешно", result.success)
        assertTrue("Перевод не должен быть пустым", result.text.isNotEmpty())
        assertEquals("Перевод должен совпадать с направлением",
            TranslateDirection.RU_TO_VI, result.direction)
        assertTrue("Backend должен быть указан", result.backend.isNotEmpty())
    }

    @Test
    fun translateViToRu_returnsRussian() = runBlocking {
        val provider = LocalPhraseTranslator()
        val result = provider.translate("cảm ơn", TranslateDirection.VI_TO_RU)

        assertTrue("VI → RU должен завершиться успешно", result.success)
        assertTrue("Перевод не должен быть пустым", result.text.isNotEmpty())
        assertEquals("Перевод должен совпадать с направлением",
            TranslateDirection.VI_TO_RU, result.direction)
    }

    @Test
    fun unknownPhrase_reportsError_notFakeTranslation() = runBlocking {
        val provider = LocalPhraseTranslator()
        val result = provider.translate("абсолютно неизвестная фраза 12345", TranslateDirection.RU_TO_VI)

        // Честное поведение: нет перевода → пользователь знает, что перевод
        // отсутствует, вместо выдуманного текста.
        assertFalse("Незнакомая фраза не должна давать ложный перевод", result.success)
    }

    @Test
    fun directionFromCode_resolvesBothDirections() {
        assertEquals("ru-vi должен разрешаться",
            TranslateDirection.RU_TO_VI, TranslateDirection.fromCode("ru-vi"))
        assertEquals("vi-ru должен разрешаться",
            TranslateDirection.VI_TO_RU, TranslateDirection.fromCode("vi-ru"))
        assertNull("Код без направления → null", TranslateDirection.fromCode("en-fr"))
    }

    @Test
    fun translator_reportsActualBackend() = runBlocking {
        // ТЗ §48: пользователь должен видеть, какой backend работает.
        val result = ServiceLocator.translator.translateText("спасибо", TranslateDirection.RU_TO_VI)

        assertTrue("Результат перевода должен быть", result.success)
        assertTrue("Должен быть указан реальный backend", result.backend.isNotEmpty())
    }
}
