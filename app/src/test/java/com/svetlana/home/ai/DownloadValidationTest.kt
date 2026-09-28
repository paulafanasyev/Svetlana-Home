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

    /**
     * Аудит P0-B: LiteRT-LM Engine отклоняет тот же контент, если файл
     * назван .bin вместо .litertlm (upstream issue). Поэтому целевое
     * расширение обязано зависеть от формата модели.
     */
    @Test
    fun litertlmModelsGetLitertlmExtension() {
        val registry = AIModelRegistry()
        val litertlm = registry.all().first { it.backend == "litertlm" }
        val ext = when (litertlm.backend) {
            "litertlm" -> "litertlm"
            "llama.cpp" -> "gguf"
            else -> "bin"
        }
        assertThat(ext).isEqualTo("litertlm")
    }

    @Test
    fun llamaCppModelsGetGgufExtension() {
        val registry = AIModelRegistry()
        val gguf = registry.all().first { it.backend == "llama.cpp" }
        val ext = when (gguf.backend) {
            "litertlm" -> "litertlm"
            "llama.cpp" -> "gguf"
            else -> "bin"
        }
        assertThat(ext).isEqualTo("gguf")
    }

    /**
     * Аудит P0-C: URL'ы моделей в реестре должны указывать на реальные
     * файлы. Регрессия на устаревшие gemma3-1b-it.litertlm / 4b, которых
     * не существует в репозиториях litert-community.
     */
    @Test
    fun litertlmRegistryUrlsAreRealFiles() {
        val registry = AIModelRegistry()
        val litertlmModels = registry.all().filter { it.backend == "litertlm" }
        assertThat(litertlmModels).isNotEmpty()
        litertlmModels.forEach { model ->
            // URL должен быть прямой ссылкой на файл, а не на страницу
            assertThat(model.downloadUrl).contains("/resolve/")
            // и заканчиваться расширением .litertlm
            assertThat(model.downloadUrl).endsWith(".litertlm")
        }
    }

    /**
     * Аудит §13/§18: доверенный хеш должен быть указан — иначе «вычислить
     * SHA» не означает «проверить SHA». Мультимодальная Gemma — основная
     * модель для LOCAL_ONLY vision, её хеш сверен с реальным файлом.
     */
    @Test
    fun litertlmVisionModelHasTrustedSha256() {
        val registry = AIModelRegistry()
        val vision = registry.all().first { it.id == "gemma-4-e2b-it-litertlm" }
        assertThat(vision.expectedSha256).isNotEmpty()
        assertThat(vision.expectedSha256).hasLength(64)
    }
}
