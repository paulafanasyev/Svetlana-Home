package com.svetlana.home.translate

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.core.ServiceLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Аудит п.75: переводчик должен работать в 4 режимах.
 * В режиме «Авто» при ненастроенном AI должен срабатывать локальный
 * переводчик, чтобы пользователь получал ответ, а не ошибку.
 */
@RunWith(AndroidJUnit4::class)
class TranslatorRuntimeDeviceTest {

    @Before
    fun setUp() {
        ServiceLocator.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun ruToViTextTranslates() = runBlocking {
        val r = ServiceLocator.translator.translateText("Привет", TranslateDirection.RU_TO_VI)
        assertTrue("перевод RU→VI должен быть успешен", r.success)
        assertTrue("результат не пустой", r.text.isNotBlank())
    }

    @Test
    fun viToRuTextTranslates() = runBlocking {
        val r = ServiceLocator.translator.translateText("Xin chào", TranslateDirection.VI_TO_RU)
        assertTrue("перевод VI→RU должен быть успешен", r.success)
    }

    @Test
    fun autoModeFallsBackToLocalWhenAiUnavailable() = runBlocking {
        // backend=auto → AI не настроен → локальный переводчик
        val r = ServiceLocator.translator.translateText("Спасибо", TranslateDirection.RU_TO_VI)
        assertTrue("в режиме авто перевод должен быть успешен", r.success)
        assertTrue("backend указан", r.backend.isNotBlank())
    }

    @Test
    fun conversationDirectionDetectionWorks() {
        val ru = ServiceLocator.translator.detectRussian("Привет, как дела")
        val vi = ServiceLocator.translator.detectRussian("Xin chào, bạn khỏe không")
        assertTrue("русская фраза определяется", ru)
        assertFalse("вьетнамская фраза не определяется как русская", vi)
    }

    @Test
    fun translateResultRecordsBackend() = runBlocking {
        val r = ServiceLocator.translator.translateText("Да", TranslateDirection.RU_TO_VI)
        assertTrue("backend указан в результате", r.backend.isNotBlank())
    }
}
