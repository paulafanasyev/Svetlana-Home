package com.svetlana.home.ai.local

import com.google.common.truth.Truth.assertThat
import com.svetlana.home.ai.AIModelRegistry
import com.svetlana.home.ai.InstalledModel
import com.svetlana.home.ai.LocalModelManager
import org.junit.Test

/**
 * Аудит §10-12 (P0-1): диспетчеризация runtime'ов по формату модели.
 *
 * Раньше проект имел один runtime (llama.cpp/GGUF), и любая локальная
 * модель шла в него. Добавление LiteRT-LM (.litertlm) требует, чтобы
 * выбор runtime'а определялся форматом выбранной модели — иначе
 * мультимодальная модель попадёт в text-only runtime и vision
 * тихо «не сработает».
 *
 * Эти тесты проверяют логику выбора без нативных слоёв: реальный
 * inference — DEVICE-тест.
 */
class CompositeRuntimeLogicTest {

    private val registry = AIModelRegistry()

    @Test
    fun litertlmModelsAreRegistered() {
        val litert = registry.all().filter {
            it.architecture.equals("litertlm", ignoreCase = true)
        }
        assertThat(litert).isNotEmpty()
        // Эти id использует CompositeInferenceRuntime
        assertThat(litert.map { it.id }).containsAtLeast(
            "gemma-4-e2b-it-litertlm", "qwen2.5-1.5b-instruct-litertlm"
        )
    }

    @Test
    fun litertlmVisionModelDeclaresVisionCapability() {
        // Это основа LOCAL_ONLY vision: без неё маршрутизатор не направит
        // изображение в локальную модель. Текстовые модели (Qwen) могут
        // не иметь VISION — проверяем только мультимодальную Gemma.
        val vision = registry.all().first { it.id == "gemma-4-e2b-it-litertlm" }
        assertThat(vision.capabilities.any { it.name == "VISION" }).isTrue()
    }

    @Test
    fun ggufModelsAreStillLlamaCppBackend() {
        // Регрессия: LiteRT не должен перехватить GGUF-модели.
        val gguf = registry.all().filter { it.backend.equals("llama.cpp", ignoreCase = true) }
        assertThat(gguf).isNotEmpty()
        assertThat(gguf.none { it.architecture.equals("litertlm", true) }).isTrue()
    }

    @Test
    fun litertlmAndGgufBackendsAreDisjoint() {
        // Ни одна модель не может принадлежать обоим runtime'ам —
        // иначе возникла бы неоднозначность выбора.
        val both = registry.all().filter { model ->
            val isLitert = model.architecture.equals("litertlm", ignoreCase = true) ||
                model.backend.equals("litertlm", ignoreCase = true)
            val isLlama = model.backend.equals("llama.cpp", ignoreCase = true)
            isLitert && isLlama
        }
        assertThat(both).isEmpty()
    }

    @Test
    fun supportedModelIdsSplitByBackend() {
        // Каждый runtime отвечает только за свой формат.
        val llamaIds = registry.all()
            .filter { it.backend.equals("llama.cpp", ignoreCase = true) }
            .map { it.id }
        val litertIds = registry.all()
            .filter {
                it.architecture.equals("litertlm", ignoreCase = true) ||
                    it.backend.equals("litertlm", ignoreCase = true)
            }
            .map { it.id }
        assertThat(llamaIds.intersect(litertIds.toSet())).isEmpty()
    }

    @Test
    fun everyModelHasDownloadUrlAndLicense() {
        // ТЗ §13: пользователь скачивает модель сам — нужен честный URL
        // и лицензия для отображения в UI.
        registry.all().forEach { model ->
            assertThat(model.downloadUrl).isNotEmpty()
            assertThat(model.license).isNotEmpty()
            assertThat(model.ramRequirementMb).isGreaterThan(0)
        }
    }

    @Test
    fun installedModelHasFilePathField() {
        // Composite runtime ищет файл модели по filePath — поле должно
        // существовать и быть непустым по контракту.
        val installed = InstalledModel(
            modelId = "gemma-4-e2b-it-litertlm",
            name = "Gemma 4 E2B IT (LiteRT)",
            filePath = "/data/local/tmp/gemma-4-e2b-it.litertlm",
            sizeBytes = 2469L * 1024 * 1024,
            installedAt = 0L
        )
        assertThat(installed.filePath).isNotEmpty()
        assertThat(installed.modelId).isEqualTo("gemma-4-e2b-it-litertlm")
    }

    @Test
    fun litertlmModelsSupportGpu() {
        // LiteRT-LM — единственный локальный runtime с GPU backend;
        // это его основное преимущество над CPU-only llama.cpp.
        val litert = registry.all().first {
            it.architecture.equals("litertlm", ignoreCase = true)
        }
        assertThat(litert.gpuSupport).isTrue()
    }
}
