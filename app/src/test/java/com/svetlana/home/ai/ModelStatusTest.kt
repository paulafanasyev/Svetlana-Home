package com.svetlana.home.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит §13/§14: строгий жизненный цикл модели и свера SHA-256.
 */
class ModelStatusTest {

    @Test
    fun `NOT_INSTALLED when nothing known`() {
        assertEquals(
            ModelStatus.NOT_INSTALLED,
            ModelStatus.fromFacts(false, false, false, false, false, false, false)
        )
    }

    @Test
    fun `INSTALLED is not FORMAT_VERIFIED`() {
        // Аудит: установленный файл ≠ проверенный формат
        assertEquals(
            ModelStatus.INSTALLED,
            ModelStatus.fromFacts(true, false, false, false, false, false, false)
        )
    }

    @Test
    fun `ladder climbs one step at a time`() {
        assertEquals(ModelStatus.FORMAT_VERIFIED,
            ModelStatus.fromFacts(true, true, false, false, false, false, false))
        assertEquals(ModelStatus.RUNTIME_READY,
            ModelStatus.fromFacts(true, true, true, false, false, false, false))
        assertEquals(ModelStatus.MODEL_LOADED,
            ModelStatus.fromFacts(true, true, true, true, false, false, false))
    }

    @Test
    fun `MODEL_LOADED is not INFERENCE_VERIFIED`() {
        // Аудит §14: ModelLoaded ≠ InferenceVerified
        val s = ModelStatus.fromFacts(true, true, true, true, false, false, false)
        assertEquals(ModelStatus.MODEL_LOADED, s)
        assertFalse("Загруженная модель ещё не проверена", s == ModelStatus.INFERENCE_VERIFIED)
    }

    @Test
    fun `inference verified but device not`() {
        // InferenceVerified ≠ DeviceVerified
        assertEquals(
            ModelStatus.INFERENCE_VERIFIED,
            ModelStatus.fromFacts(true, true, true, true, true, false, false)
        )
    }

    @Test
    fun `DEVICE_VERIFIED is the top`() {
        assertEquals(
            ModelStatus.DEVICE_VERIFIED,
            ModelStatus.fromFacts(true, true, true, true, true, true, true)
        )
    }

    @Test
    fun `GGUF magic matches GGUF header`() {
        // "GGUF" = 0x47 0x47 0x55 0x46
        val header = byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x01, 0x00)
        assertTrue(ModelFormat.GGUF.matchesMagic(header))
    }

    @Test
    fun `HTML 404 page is not GGUF`() {
        // Реальный сценарий аудита п.9: вместо модели скачалась HTML-страница
        val html = "<!DOCTYPE html><html><body>404</body></html>".toByteArray()
        assertFalse(ModelFormat.GGUF.matchesMagic(html))
    }

    @Test
    fun `short header does not match`() {
        assertFalse(ModelFormat.GGUF.matchesMagic(byteArrayOf(0x47, 0x47)))
    }
}
