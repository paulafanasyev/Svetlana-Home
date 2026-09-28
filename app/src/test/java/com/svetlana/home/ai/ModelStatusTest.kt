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

    /**
     * Аудит P0-A: LITERT_LM раньше ошибочно проверял "TFL" по смещению 4 —
     * это заголовок .tflite, а не контейнер LiteRT-LM. Реальный контейнер
     * начинается с 8-байтного ASCII "LITERTLM" по нулевому смещению
     * (runtime/util/file_format_util.cc, litertlm_header.h).
     */
    @Test
    fun `LITERT_LM matches real litertlm container header`() {
        // Реальный заголовок test_lm.litertlm из upstream testdata:
        // 4c 49 54 45 52 54 4c 4d | 01 00 00 00 | 06 00 00 00
        //      L  I  T  E  R  T  L  M    v1.0.0
        val header = byteArrayOf(
            0x4c, 0x49, 0x54, 0x45, 0x52, 0x54, 0x4c, 0x4d,
            0x01, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00
        )
        assertTrue(ModelFormat.LITERT_LM.matchesMagic(header))
    }

    @Test
    fun `LITERT_LM rejects tflite file`() {
        // TFL3 по смещению 4 — это .tflite, НЕ LiteRT-LM.
        // Прежняя ошибочная проверка принимала бы этот файл.
        val header = byteArrayOf(0x28, 0x00, 0x00, 0x00, 0x54, 0x46, 0x4c, 0x33)
        assertFalse(ModelFormat.LITERT_LM.matchesMagic(header))
    }

    @Test
    fun `LITERT_LM rejects HTML 404 page`() {
        val html = "<!DOCTYPE html><html><body>404</body></html>".toByteArray()
        assertFalse(ModelFormat.LITERT_LM.matchesMagic(html.copyOf(8)))
    }

    @Test
    fun `LITERT_LM rejects garbage`() {
        val header = ByteArray(16) { 0xFF.toByte() }
        assertFalse(ModelFormat.LITERT_LM.matchesMagic(header))
    }

    @Test
    fun `LITERT_LM rejects truncated header`() {
        // Файл короче 8 байт не может содержать magic
        val header = byteArrayOf(0x4c, 0x49, 0x54, 0x45)
        assertFalse(ModelFormat.LITERT_LM.matchesMagic(header))
    }

    @Test
    fun `LITERT_LM magic is no longer empty`() {
        // Регрессия: пустой magicHex означал «принять всё»
        assertFalse(ModelFormat.LITERT_LM.magicHex.isEmpty())
    }

    @Test
    fun `LITERT_LM magic is exactly LITERTLM`() {
        // 8 байт: L I T E R T L M
        assertEquals("4c49544552544c4d", ModelFormat.LITERT_LM.magicHex)
        assertEquals(8, ModelFormat.LITERT_LM.magicHex.length / 2)
    }
}
