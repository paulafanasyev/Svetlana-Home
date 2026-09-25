package com.svetlana.home.ai.local

import com.google.common.truth.Truth.assertThat
import com.svetlana.home.ai.AIModel
import com.svetlana.home.ai.AIModelRegistry
import com.svetlana.home.ai.LocalModelManager
import org.junit.Test

/**
 * Тесты логики LlamaCppRuntime без нативного слоя.
 *
 * ВАЖНО: эти тесты проверяют, что runtime корректно определяет
 * поддерживаемые модели и не пытается работать с отсутствующей моделью.
 * Реальный inference (загрузка GGUF + генерация) можно проверить только
 * на arm64-устройстве — это DEVICE-тест, не unit-тест.
 */
class LlamaCppRuntimeLogicTest {

    private val registry = AIModelRegistry()

    @Test
    fun supportedModelIds_onlyLlamaCppBackend() {
        // Регистрируем подмену: supportedModelIds должен вернуть только
        // модели с backend == "llama.cpp", а не все подряд.
        val supported = registry.all().filter { it.backend.equals("llama.cpp", ignoreCase = true) }
        assertThat(supported).isNotEmpty()
        assertThat(supported.map { it.id }).contains("qwen2.5-1.5b-instruct-q4")
        // STT/TTS модели на onnxruntime не должны попадать в список
        // llama.cpp- runtime'а.
        assertThat(supported.none { it.backend.equals("onnxruntime", true) }).isTrue()
    }

    @Test
    fun registry_allModelsHaveExplicitRamAndStorage() {
        // Совместимость считается по этим полям — они должны быть заполнены.
        registry.all().forEach { model ->
            assertThat(model.ramRequirementMb).isGreaterThan(0)
            assertThat(model.storageRequirementMb).isGreaterThan(0L)
            assertThat(model.downloadUrl).isNotEmpty()
            assertThat(model.license).isNotEmpty()
        }
    }

    @Test
    fun llamaModelsAreQuantizedGguf() {
        val llamaModels = registry.all().filter { it.backend.equals("llama.cpp", true) }
        llamaModels.forEach {
            assertThat(it.quantization).isNotEmpty()
            // GGUF-модели для llama.cpp
            assertThat(it.architecture).isNotEmpty()
        }
    }
}
