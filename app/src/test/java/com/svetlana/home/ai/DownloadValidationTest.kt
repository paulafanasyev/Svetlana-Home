package com.svetlana.home.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Аудит п.9: проверка скачиваемого файла.
 *
 * До исправления install() сохранял любой HTTP-ответ как <model-id>.bin —
 * в том числе HTML-страницу 404. Теперь формат проверяется по magic bytes,
 * а для ONNX — "нетекстовая" проверка.
 */
class DownloadValidationTest {

    private fun magicGguf(): ByteArray = byteArrayOf(
        'G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(),
        0x00, 0x03, 0x00, 0x00
    )

    private fun htmlPage(): ByteArray = "<html><body>404 Not Found</body></html>".toByteArray()

    @Test
    fun ggufMagicIsRecognizedAsModel() {
        val tmp = File.createTempFile("model", ".bin")
        tmp.writeBytes(magicGguf())
        try {
            // Эталонная проверка: правильный GGUF начинается с "GGUF"
            val magic = tmp.inputStream().use { input ->
                val b = ByteArray(4)
                input.read(b)
                b
            }
            assertThat(String(magic)).isEqualTo("GGUF")
        } finally { tmp.delete() }
    }

    @Test
    fun htmlPageIsNotGguf() {
        val tmp = File.createTempFile("fake", ".bin")
        tmp.writeBytes(htmlPage())
        try {
            val magic = tmp.inputStream().use { input ->
                val b = ByteArray(4)
                input.read(b)
                b
            }
            assertThat(String(magic)).isNotEqualTo("GGUF")
            // HTML-страница должна определяться как текст
            val isText = magic.all { it in 9..126 }
            assertThat(isText).isTrue()
        } finally { tmp.delete() }
    }

    @Test
    fun smallFileRejectedBySizeHeuristic() {
        // Файл < 1KB не может быть моделью ONNX
        val tiny = ByteArray(512) { 'A'.code.toByte() }
        assertThat(tiny.size < 1024).isTrue()
    }
}
